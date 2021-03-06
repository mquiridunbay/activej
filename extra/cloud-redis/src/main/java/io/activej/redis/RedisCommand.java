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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

final class RedisCommand {
	private final Command command;
	private final List<byte[]> arguments;

	private RedisCommand(Command command, List<byte[]> arguments) {
		this.command = command;
		this.arguments = arguments;
	}

	static RedisCommand of(Command command, Charset charset, List<String> arguments) {
		List<byte[]> list = new ArrayList<>(arguments.size());
		for (String arg : arguments) {
			list.add(arg.getBytes(charset));
		}
		return new RedisCommand(command, list);
	}

	static RedisCommand of(Command command, Charset charset, String... arguments) {
		List<byte[]> list = new ArrayList<>(arguments.length);
		for (String arg : arguments) {
			list.add(arg.getBytes(charset));
		}
		return new RedisCommand(command, list);
	}

	static RedisCommand of(Command command, List<byte[]> arguments) {
		return new RedisCommand(command, arguments);
	}

	static RedisCommand of(Command command, byte[]... arguments) {
		return new RedisCommand(command, asList(arguments));
	}

	Command getCommand() {
		return command;
	}

	List<byte[]> getArguments() {
		return arguments;
	}

	@Override
	public String toString() {
		if (arguments.size() == 0) return "'" + command + '\'';
		return "'" + command + ' ' + arguments.stream().map(String::new).collect(joining(" ")) + '\'';
	}
}
