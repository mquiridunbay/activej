package io.activej.remotefs.cluster;

import io.activej.async.function.AsyncConsumer;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.StacklessException;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.eventloop.Eventloop;
import io.activej.net.AbstractServer;
import io.activej.promise.Promises;
import io.activej.remotefs.FsClient;
import io.activej.remotefs.RemoteFsClient;
import io.activej.remotefs.RemoteFsServer;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public final class TestRemoteFsClusterClient {
	public static final int CLIENT_SERVER_PAIRS = 10;

	@Rule
	public final TemporaryFolder tmpFolder = new TemporaryFolder();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Path[] serverStorages = new Path[CLIENT_SERVER_PAIRS];

	private List<RemoteFsServer> servers;
	private Path clientStorage;
	private RemoteFsClusterClient client;

	@Before
	public void setup() throws IOException {
		Executor executor = Executors.newSingleThreadExecutor();
		servers = new ArrayList<>(CLIENT_SERVER_PAIRS);
		clientStorage = Paths.get(tmpFolder.newFolder("client").toURI());

		Files.createDirectories(clientStorage);

		Map<Object, FsClient> clients = new HashMap<>(CLIENT_SERVER_PAIRS);

		Eventloop eventloop = Eventloop.getCurrentEventloop();

		for (int i = 0; i < CLIENT_SERVER_PAIRS; i++) {
			InetSocketAddress address = new InetSocketAddress("localhost", 5600 + i);

			clients.put("server_" + i, RemoteFsClient.create(eventloop, address));

			serverStorages[i] = Paths.get(tmpFolder.newFolder("storage_" + i).toURI());
			Files.createDirectories(serverStorages[i]);

			RemoteFsServer server = RemoteFsServer.create(eventloop, executor, serverStorages[i]).withListenAddress(address);
			server.listen();
			servers.add(server);
		}

		clients.put("dead_one", RemoteFsClient.create(eventloop, new InetSocketAddress("localhost", 5555)));
		clients.put("dead_two", RemoteFsClient.create(eventloop, new InetSocketAddress("localhost", 5556)));
		clients.put("dead_three", RemoteFsClient.create(eventloop, new InetSocketAddress("localhost", 5557)));
		client = RemoteFsClusterClient.create(eventloop, clients);
		client.withReplicationCount(4); // there are those 3 dead nodes added above
	}

	@Test
	public void testUpload() throws IOException {
		String content = "test content of the file";
		String resultFile = "file.txt";

		await(client.upload(resultFile)
				.then(ChannelSupplier.of(ByteBuf.wrapForReading(content.getBytes(UTF_8)))::streamTo)
				.whenComplete(() -> servers.forEach(AbstractServer::close)));

		int uploaded = 0;
		for (int i = 0; i < CLIENT_SERVER_PAIRS; i++) {
			Path path = serverStorages[i].resolve(resultFile);
			if (Files.exists(path)) {
				assertEquals(new String(readAllBytes(path), UTF_8), content);
				uploaded++;
			}
		}
		assertEquals(4, uploaded); // replication count

	}

	@Test
	public void testDownload() throws IOException {
		int numOfServer = 3;
		String file = "the_file.txt";
		String content = "another test content of the file";

		Files.write(serverStorages[numOfServer].resolve(file), content.getBytes(UTF_8));

		await(ChannelSupplier.ofPromise(client.download(file))
				.streamTo(ChannelFileWriter.open(newCachedThreadPool(), clientStorage.resolve(file)))
				.whenComplete(() -> servers.forEach(AbstractServer::close)));

		assertEquals(new String(readAllBytes(clientStorage.resolve(file)), UTF_8), content);
	}

	@Test
	public void testUploadSelector() throws IOException {
		String content = "test content of the file";
		ByteBuf data = ByteBuf.wrapForReading(content.getBytes(UTF_8));

		client.withReplicationCount(1)
				.withServerSelector((fileName, serverKeys) -> {
					String firstServer = fileName.contains("1") ?
							"server_1" :
							fileName.contains("2") ?
									"server_2" :
									fileName.contains("3") ?
											"server_3" :
											"server_0";
					return serverKeys.stream()
							.map(String.class::cast)
							.sorted(Comparator.comparing(key -> key.equals(firstServer) ? -1 : 1))
							.collect(toList());
				});

		String[] files = {"file_1.txt", "file_2.txt", "file_3.txt", "other.txt"};

		await(Promises.all(Arrays.stream(files).map(f -> ChannelSupplier.of(data.slice()).streamTo(ChannelConsumer.ofPromise(client.upload(f)))))
				.whenComplete(() -> servers.forEach(AbstractServer::close)));

		assertEquals(new String(readAllBytes(serverStorages[1].resolve("file_1.txt")), UTF_8), content);
		assertEquals(new String(readAllBytes(serverStorages[2].resolve("file_2.txt")), UTF_8), content);
		assertEquals(new String(readAllBytes(serverStorages[3].resolve("file_3.txt")), UTF_8), content);
		assertEquals(new String(readAllBytes(serverStorages[0].resolve("other.txt")), UTF_8), content);
	}

	@Test
	@Ignore("this test uses lots of local ports (and all of them are in TIME_WAIT state after it for a minute) so HTTP tests after it may fail indefinitely")
	public void testUploadAlot() throws IOException {
		String content = "test content of the file";
		ByteBuf data = ByteBuf.wrapForReading(content.getBytes(UTF_8));

		await(Promises.sequence(IntStream.range(0, 1000)
				.mapToObj(i ->
						() -> ChannelSupplier.of(data.slice())
								.streamTo(ChannelConsumer.ofPromise(client.upload("file_uploaded_" + i + ".txt")))))
				.whenComplete(() -> servers.forEach(AbstractServer::close)));

		for (int i = 0; i < CLIENT_SERVER_PAIRS; i++) {
			for (int j = 0; j < 1000; j++) {
				assertEquals(new String(readAllBytes(serverStorages[i].resolve("file_uploaded_" + i + ".txt")), UTF_8), content);
			}
		}
	}

	@Test
	public void testNotEnoughUploads() {
		client.withReplicationCount(client.getClients().size()); // max possible replication

		Throwable exception = awaitException(ChannelSupplier.of(ByteBuf.wrapForReading("whatever, blah-blah".getBytes(UTF_8))).streamTo(ChannelConsumer.ofPromise(client.upload("file_uploaded.txt")))
				.whenComplete(() -> servers.forEach(AbstractServer::close)));

		assertThat(exception, instanceOf(StacklessException.class));
		assertThat(exception.getMessage(), containsString("Didn't connect to enough partitions"));
	}

	@Test
	public void downloadNonExisting() {
		String fileName = "i_dont_exist.txt";

		Throwable exception = awaitException(ChannelSupplier.ofPromise(client.download(fileName))
				.streamTo(ChannelConsumer.of(AsyncConsumer.of(ByteBuf::recycle)))
				.whenComplete(() -> servers.forEach(AbstractServer::close)));

		assertThat(exception, instanceOf(StacklessException.class));
		assertThat(exception.getMessage(), containsString(fileName));
	}
}
