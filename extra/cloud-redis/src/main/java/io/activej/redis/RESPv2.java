/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.redis;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.InvalidSizeException;
import io.activej.common.exception.MalformedDataException;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.activej.bytebuf.ByteBufStrings.CR;
import static io.activej.bytebuf.ByteBufStrings.LF;
import static io.activej.csp.binary.ByteBufsDecoder.ofCrlfTerminatedBytes;
import static java.lang.Math.min;
import static java.util.Collections.emptyList;

final class RESPv2 implements RedisProtocol {

	private static final int CR_LF_LENGTH = 2;
	private static final int INTEGER_MAX_LEN = String.valueOf(Long.MIN_VALUE).length();
	private static final int STRING_MAX_LEN = 512 * 1024 * 1024; // 512 MB

	private static final byte STRING_MARKER = '+';
	private static final byte ERROR_MARKER = '-';
	private static final byte INTEGER_MARKER = ':';
	private static final byte BULK_STRING_MARKER = '$';
	private static final byte ARRAY_MARKER = '*';

	private static final List<?> NIL_ARRAY = new ArrayList<>();
	private static final byte[] NIL_BULK_STRING = {};

	private final Charset charset;

	private final ByteBufs tempBufs;
	private final List<byte[]> args = new ArrayList<>();

	private byte parsing;
	private int remaining = -1;
	@Nullable
	private List<Integer> arraysRemaining;
	@Nullable
	private List<Object> arrayResult;

	public RESPv2(ByteBufs tempBufs, Charset charset) {
		this.tempBufs = tempBufs;
		this.charset = charset;
	}

	@Override
	public int encode(byte[] array, int offset, RedisCommand item) {
		Command command = item.getCommand();

		args.clear();
		args.addAll(command.getParts());
		args.addAll(item.getArguments());

		array[offset++] = ARRAY_MARKER;
		byte[] arrayLenBytes = String.valueOf(args.size()).getBytes(charset);
		System.arraycopy(arrayLenBytes, 0, array, offset, arrayLenBytes.length);
		offset += arrayLenBytes.length;

		array[offset++] = CR;
		array[offset++] = LF;

		for (byte[] argument : args) {
			array[offset++] = BULK_STRING_MARKER;

			byte[] argLenBytes = String.valueOf(argument.length).getBytes(charset);
			System.arraycopy(argLenBytes, 0, array, offset, argLenBytes.length);
			offset += argLenBytes.length;

			array[offset++] = CR;
			array[offset++] = LF;

			System.arraycopy(argument, 0, array, offset, argument.length);
			offset += argument.length;

			array[offset++] = CR;
			array[offset++] = LF;
		}

		return offset;
	}

	@Nullable
	@Override
	public RedisResponse tryDecode(ByteBufs bufs) throws MalformedDataException {
		while (true) {
			if (bufs.isEmpty()) return null;
			if (parsing == 0) parsing = bufs.getByte();

			RedisResponse result = null;

			switch (parsing) {
				case STRING_MARKER:
					String string = decodeString(bufs, STRING_MAX_LEN);
					if (string != null) {
						result = addToArrayOr(string, RedisResponse::string);
					} else {
						return null;
					}
					break;
				case ERROR_MARKER:
					String message = decodeString(bufs, STRING_MAX_LEN);
					if (message != null) {
						ServerError error = new ServerError(message);
						result = addToArrayOr(error, RedisResponse::error);
					} else {
						return null;
					}
					break;
				case INTEGER_MARKER:
					String integer = decodeString(bufs, INTEGER_MAX_LEN);
					if (integer != null) {
						try {
							long value = Long.parseLong(integer);
							result = addToArrayOr(value, RedisResponse::integer);
						} catch (NumberFormatException e) {
							throw new MalformedDataException("Malformed integer " + integer, e);
						}
					} else {
						return null;
					}
					break;
				case BULK_STRING_MARKER:
					byte[] bulkStringBytes = decodeBulkString(bufs);
					if (bulkStringBytes == NIL_BULK_STRING) {
						result = addToArrayOr(null, $ -> RedisResponse.nil());
					} else if (bulkStringBytes != null) {
						result = addToArrayOr(bulkStringBytes, RedisResponse::bytes);
					} else {
						return null;
					}
					break;
				case ARRAY_MARKER:
					int before = bufs.remainingBytes();
					List<?> array = decodeArray(bufs);
					if (array == NIL_ARRAY) {
						result = addToArrayOr(null, $ -> RedisResponse.nil());
					} else if (array != null) {
						result = addToArrayOr(array, RedisResponse::array);
					} else if (before == bufs.remainingBytes()) {
						// parsed nothing
						return null;
					}
					break;
				default:
					throw new MalformedDataException("Unknown first byte '" + (char) parsing + "'");
			}

			if (result != null) return result;
		}
	}

	@Nullable
	private String decodeString(ByteBufs bufs, int maxSize) throws MalformedDataException {
		ByteBuf decoded = ofCrlfTerminatedBytes(maxSize + CR_LF_LENGTH).tryDecode(bufs);

		if (decoded != null) {
			tempBufs.add(decoded);
			return tempBufs.takeRemaining().asString(charset);
		}

		if (!bufs.isEmpty()) {
			tempBufs.add(bufs.takeExactSize(bufs.remainingBytes() - 1));
		}
		return null;
	}

	private @Nullable byte [] decodeBulkString(ByteBufs bufs) throws MalformedDataException {
		if (remaining == -1) {
			Integer length = decodeLength(bufs);
			if (length == null) return null;
			if (length == -1) {
				parsing = 0;
				return NIL_BULK_STRING;
			}
			remaining = length;
		}

		ByteBuf result = ByteBufPool.allocate(min(bufs.remainingBytes(), remaining));
		remaining -= bufs.drainTo(result, remaining);
		tempBufs.add(result);

		if (remaining == 0) {
			if (!bufs.hasRemainingBytes(2)) {
				return null;
			} else {
				if (bufs.getByte() != CR || bufs.getByte() != LF) {
					throw new MalformedDataException("Missing CR LF");
				}
				remaining = -1;
				return tempBufs.takeRemaining().asArray();
			}
		}
		return null;
	}

	@Nullable
	@SuppressWarnings("unchecked")
	private List<?> decodeArray(ByteBufs bufs) throws MalformedDataException {
		Integer length = decodeLength(bufs);
		if (length == null) return null;
		parsing = 0;
		if (length == -1) return NIL_ARRAY;
		if (length != 0) {
			if (arraysRemaining == null) {
				arraysRemaining = new ArrayList<>();
				arrayResult = new ArrayList<>();
			} else {
				assert arrayResult != null;
				List<Object> array = arrayResult;
				for (int i = 0; i < arraysRemaining.size() - 1; i++) {
					array = (List<Object>) array.get(array.size() - 1);
				}

				array.add(new ArrayList<>());
			}
			arraysRemaining.add(length);
			return null;
		}
		return emptyList();

	}

	@Nullable
	private Integer decodeLength(ByteBufs bufs) throws MalformedDataException {
		String numString = decodeString(bufs, INTEGER_MAX_LEN);
		if (numString == null) return null;

		int len;
		try {
			len = Integer.parseInt(numString);
		} catch (NumberFormatException e) {
			throw new MalformedDataException("Malformed length: '" + numString + '\'', e);
		}

		if (len < -1) {
			throw new InvalidSizeException("Unsupported negative length: '" + len + '\'');
		}

		return len;
	}

	@Nullable
	@SuppressWarnings("unchecked")
	private <T> RedisResponse addToArrayOr(T value, Function<T, RedisResponse> fn) {
		parsing = 0;
		if (arrayResult == null) {
			assert arraysRemaining == null;
			return fn.apply(value);
		}
		assert arraysRemaining != null;

		List<Object> array = arrayResult;
		for (int i = 0; i < arraysRemaining.size() - 1; i++) {
			array = (List<Object>) array.get(array.size() - 1);
		}
		array.add(value);

		int index = arraysRemaining.size() - 1;
		while (true) {
			Integer remaining = arraysRemaining.get(index);
			if (remaining == 1) {
				arraysRemaining.remove(index--);
				if (arraysRemaining.isEmpty()) {
					List<?> arrayResult = this.arrayResult;
					this.arrayResult = null;
					this.arraysRemaining = null;
					return RedisResponse.array(arrayResult);
				}
			} else {
				arraysRemaining.set(index, remaining - 1);
				return null;
			}
		}
	}

}
