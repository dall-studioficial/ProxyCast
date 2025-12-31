/*
 * Copyright 2025 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dall.app.proxycast.core

import android.util.Log

/**
 * Lightweight Timber-like logging facade
 * TODO: Replace with actual Timber library or integrate with existing logging
 */
object Timber {
    private const val TAG = "ProxyCast"

    fun d(message: () -> String) {
        Log.d(TAG, message())
    }

    fun d(throwable: Throwable, message: () -> String) {
        Log.d(TAG, message(), throwable)
    }

    fun w(message: () -> String) {
        Log.w(TAG, message())
    }

    fun w(throwable: Throwable, message: () -> String) {
        Log.w(TAG, message(), throwable)
    }

    fun e(throwable: Throwable, message: () -> String) {
        Log.e(TAG, message(), throwable)
    }

    fun e(message: () -> String) {
        Log.e(TAG, message())
    }

    fun i(message: () -> String) {
        Log.i(TAG, message())
    }
}
