package com.mocharealm.foundation.fabric.buffer

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

internal fun withHttpResponse(body: String, status: Int = 200, test: (String) -> Unit): String {
    val request = AtomicReference("")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/classify") { exchange ->
        try {
            request.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
    }
    server.start()
    try { test("http://127.0.0.1:${server.address.port}/classify") }
    finally { server.stop(0) }
    return request.get()
}
