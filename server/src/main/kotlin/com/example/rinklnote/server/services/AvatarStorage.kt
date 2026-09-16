package com.example.rinklnote.server.services

import java.io.File

/**
 * 头像文件存储：`{baseDir}/avatars/{userId}.jpg`（baseDir 默认相对服务端工作目录的 `uploads`）。
 *
 * 设计要点：
 * - 一人一文件：无论源图是 JPEG/PNG/WebP，一律落成 `{userId}.jpg`（经 static 路由对外提供）；
 * - 覆盖写：先写临时文件再原子改名替换，旧文件随之消失（等价于「覆盖时删旧文件」）；
 * - 防多端缓存：`publicUrl` 附加 `?v={文件最后修改毫秒}`，重传后 URL 变化，Coil/浏览器缓存自然失效；
 * - 测试可注入 baseDir（JUnit 临时目录），避免污染工作目录。
 */
class AvatarStorage(private val baseDir: String = "uploads") {

    private val avatarDir: File get() = File(baseDir, "avatars")

    /** 上传接口允许的 Content-Type（与路由层校验共用一份口径）。 */
    companion object {
        val ALLOWED_CONTENT_TYPES = setOf(
            io.ktor.http.ContentType.Image.JPEG,
            io.ktor.http.ContentType.Image.PNG,
            io.ktor.http.ContentType.parse("image/webp")
        )
        /** 单文件大小上限：2MB。 */
        const val MAX_BYTES: Int = 2 * 1024 * 1024
    }

    /** 预热目录（服务启动时调用；save 内部也会兜底 mkdirs）。 */
    fun ensureDirs() {
        avatarDir.mkdirs()
    }

    /**
     * 保存头像字节：写临时文件 → 删旧 → 原子改名。返回落盘文件。
     * 调用方（路由层）负责先完成类型/大小校验。
     */
    fun save(userId: Long, bytes: ByteArray): File {
        avatarDir.mkdirs()
        val target = File(avatarDir, "$userId.jpg")
        val tmp = File(avatarDir, "$userId.jpg.tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 改名失败（极少见）：兜底直接写目标文件，保证功能可用。
            target.writeBytes(bytes)
            tmp.delete()
        }
        return target
    }

    /** 头像文件是否存在。 */
    fun exists(userId: Long): Boolean = File(avatarDir, "$userId.jpg").exists()

    /** 对外相对 URL（含 ?v= 版本参数）；未上传过返回 null。 */
    fun publicUrl(userId: Long): String? {
        val file = File(avatarDir, "$userId.jpg")
        if (!file.exists()) return null
        return "uploads/avatars/$userId.jpg?v=${file.lastModified()}"
    }
}
