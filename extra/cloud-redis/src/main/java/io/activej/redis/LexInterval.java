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

public final class LexInterval {
	private static final String NEGATIVE_INFINITY = "-";
	private static final String POSITIVE_INFINITY = "+";

	private final String min;
	private final String max;

	private LexInterval(String min, String max) {
		this.min = min;
		this.max = max;
	}

	public static LexInterval interval(String min, String max) {
		return new LexInterval(toInterval(min, false), toInterval(max, false));
	}

	public static LexInterval interval(String min, String max, boolean minExcluded, boolean maxExcluded) {
		return new LexInterval(toInterval(min, minExcluded), toInterval(max, maxExcluded));
	}

	public static LexInterval to(String max, boolean maxExcluded) {
		return new LexInterval(NEGATIVE_INFINITY, toInterval(max, maxExcluded));
	}

	public static LexInterval to(String max) {
		return new LexInterval(NEGATIVE_INFINITY, toInterval(max, false));
	}

	public static LexInterval from(String min, boolean minExcluded) {
		return new LexInterval(toInterval(min, minExcluded), POSITIVE_INFINITY);
	}

	public static LexInterval from(String min) {
		return new LexInterval(toInterval(min, false), POSITIVE_INFINITY);
	}

	public static LexInterval all() {
		return new LexInterval(NEGATIVE_INFINITY, POSITIVE_INFINITY);
	}

	public LexInterval reverse() {
		return new LexInterval(max, min);
	}

	public String getMin() {
		return min;
	}

	public String getMax() {
		return max;
	}

	private static String toInterval(String value, boolean valueExcluded) {
		return (valueExcluded ? '(' : '[') + value;
	}
}
