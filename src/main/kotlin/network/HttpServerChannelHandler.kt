package com.fengsheng.network

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.util.CharsetUtil
import org.apache.commons.text.CaseUtils.toCamelCase
import org.apache.logging.log4j.kotlin.logger
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.function.Function
import javax.imageio.ImageIO

class HttpServerChannelHandler : SimpleChannelInboundHandler<HttpObject>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        if (msg is HttpRequest) {
            if (msg.method() !== HttpMethod.GET) {
                val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "invalid method")), CharsetUtil.UTF_8)
                val response: FullHttpResponse =
                    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED, byteBuf)
                response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                response.headers().add(HttpHeaderNames.CONNECTION, "close")
                ctx.writeAndFlush(response)
            } else {
                try {
                    val uri = URI(msg.uri())
                    logger.info("GM HTTP receive: $uri")
                    val form = HashMap<String, String>()
                    val query = uri.rawQuery
                    if (query != null) {
                        for (s in query.split("&".toRegex()).dropLastWhile { it.isEmpty() }) {
                            val arr = s.split("=".toRegex(), limit = 2)
                            val value = if (arr.size >= 2) URLDecoder.decode(arr[1], Charsets.UTF_8) else ""
                            form.putIfAbsent(arr[0], value)
                        }
                    }
                    val name = toCamelCase(uri.path.replace("/", ""), true, '_')
                    val cls = this.javaClass.classLoader.loadClass("com.fengsheng.gm.$name")

                    @Suppress("UNCHECKED_CAST")
                    val handler = cls.getDeclaredConstructor().newInstance() as Function<Map<String, String>, Any>
                    val (contentType, byteBuf) = when (val resp = handler.apply(form)) {
                        is String -> {
                            "application/json" to Unpooled.copiedBuffer(resp, CharsetUtil.UTF_8)
                        }

                        is BufferedImage -> {
                            val os = ByteArrayOutputStream()
                            ImageIO.write(resp, "png", os)
                            "image/png" to Unpooled.copiedBuffer(os.toByteArray())
                        }

                        else -> {
                            val error = "unsupported content type"
                            "application/json" to Unpooled.copiedBuffer(gson.toJson(mapOf("error" to error)), CharsetUtil.UTF_8)
                        }
                    }
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType)
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                } catch (e: URISyntaxException) {
                    val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "parse form failed")), CharsetUtil.UTF_8)
                    val response =
                        DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                } catch (e: ClassNotFoundException) {
                    val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "404 not found")), CharsetUtil.UTF_8)
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                } catch (e: InvocationTargetException) {
                    val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "404 not found")), CharsetUtil.UTF_8)
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                } catch (e: InstantiationException) {
                    val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "404 not found")), CharsetUtil.UTF_8)
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                } catch (e: IllegalAccessException) {
                    val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "404 not found")), CharsetUtil.UTF_8)
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                } catch (e: NoSuchMethodException) {
                    val byteBuf = Unpooled.copiedBuffer(gson.toJson(mapOf("error" to "404 not found")), CharsetUtil.UTF_8)
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, byteBuf)
                    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes())
                    response.headers().add(HttpHeaderNames.CONNECTION, "close")
                    ctx.writeAndFlush(response)
                }
            }
        }
    }

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    }
}
