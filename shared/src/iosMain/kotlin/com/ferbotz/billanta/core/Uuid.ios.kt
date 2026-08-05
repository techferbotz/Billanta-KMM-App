package com.ferbotz.billanta.core

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()
