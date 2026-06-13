package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject

class MediaPlugin : McpPlugin {
    override val id: String = "media"
    override val name: String = "Media"
    override val description: String = "Access photos, videos, and audio on the device"
    
    override val requiredPermissions: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override fun getTools(): List<McpTool> = listOf(
        ListImagesTool(),
        ListVideosTool(),
        ListAudioTool()
    )

    class ListImagesTool : McpTool {
        override val name: String = "list_images"
        override val description: String = "Lists recently added images"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("limit", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val limit = arguments.optInt("limit", 20)
            val images = JSONArray()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    images.put(JSONObject()
                        .put("id", cursor.getLong(idIdx))
                        .put("name", cursor.getString(nameIdx))
                        .put("date_added", cursor.getLong(dateIdx))
                        .put("size", cursor.getLong(sizeIdx))
                    )
                    count++
                }
            }
            return JSONObject().put("images", images)
        }
    }

    class ListVideosTool : McpTool {
        override val name: String = "list_videos"
        override val description: String = "Lists recently added videos"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("limit", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val limit = arguments.optInt("limit", 20)
            val videos = JSONArray()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
            
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    videos.put(JSONObject()
                        .put("id", cursor.getLong(idIdx))
                        .put("name", cursor.getString(nameIdx))
                        .put("duration_ms", cursor.getLong(durIdx))
                        .put("date_added", cursor.getLong(dateIdx))
                    )
                    count++
                }
            }
            return JSONObject().put("videos", videos)
        }
    }

    class ListAudioTool : McpTool {
        override val name: String = "list_audio"
        override val description: String = "Lists recently added audio files"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("limit", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val limit = arguments.optInt("limit", 20)
            val audio = JSONArray()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    audio.put(JSONObject()
                        .put("id", cursor.getLong(idIdx))
                        .put("title", cursor.getString(titleIdx))
                        .put("artist", cursor.getString(artistIdx))
                        .put("date_added", cursor.getLong(dateIdx))
                    )
                    count++
                }
            }
            return JSONObject().put("audio", audio)
        }
    }
}
