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

import android.os.Looper

/**
 * Thread enforcement utility
 * TODO: Replace with actual pydroid ThreadEnforcer or remove if not needed
 */
class ThreadEnforcer {
    fun assertOffMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Must be called off main thread")
        }
    }

    fun assertOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("Must be called on main thread")
        }
    }
}
