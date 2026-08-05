package com.ferbotz.billanta.core

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString().lowercase()
