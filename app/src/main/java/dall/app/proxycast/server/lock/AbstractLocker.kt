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

package dall.app.proxycast.server.lock

import dall.app.proxycast.core.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Base class for lock implementations
 */
internal abstract class AbstractLocker : Locker

/**
 * Base class for lock instances
 */
internal abstract class AbstractLock(
    private val lockType: String,
    private val lockTag: String,
) : Locker.Lock {

    private val mutex = Mutex()
    private var refCount = 0

    protected abstract suspend fun isHeld(): Boolean

    protected abstract suspend fun onAcquireLock()

    protected abstract suspend fun onReleaseLock()

    final override suspend fun acquire(): Locker.Lock.Releaser =
        withContext(context = Dispatchers.Default) {
            mutex.withLock {
                if (refCount == 0) {
                    if (isHeld()) {
                        Timber.w { "$lockType Lock is already held but refCount was 0: $lockTag" }
                    } else {
                        Timber.d { "Acquire $lockType Lock: $lockTag" }
                        onAcquireLock()
                    }
                }
                ++refCount
                Timber.d { "$lockType Lock refCount: $refCount $lockTag" }
            }

            return@withContext Locker.Lock.Releaser { release() }
        }

    final override suspend fun release() =
        withContext(context = Dispatchers.Default) {
            mutex.withLock {
                if (refCount == 0) {
                    Timber.w { "$lockType Lock refCount was already 0: $lockTag" }
                } else {
                    --refCount
                    Timber.d { "$lockType Lock refCount: $refCount $lockTag" }
                    if (refCount == 0) {
                        if (isHeld()) {
                            Timber.d { "Release $lockType Lock: $lockTag" }
                            onReleaseLock()
                        } else {
                            Timber.w { "$lockType Lock not held but refCount was >0: $lockTag" }
                        }
                    }
                }
            }
        }
}
