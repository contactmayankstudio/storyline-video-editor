package com.video.engine.camera

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader

data class LensItem(
    val id: String,
    val name: String,
    val nameHi: String,
    val category: String,
    val anchor: String,
    val assetPath: String,
    val secondaryAssetPath: String = "",
    val iconAssetPath: String = ""
)

object LensRepository {

    private var cachedLenses: List<LensItem>? = null

    fun getLenses(context: Context): List<LensItem> {
        cachedLenses?.let { return it }

        val list = mutableListOf<LensItem>()

        // 1. Clean Camera Option
        list.add(
            LensItem(
                id = "none",
                name = "Clean Camera",
                nameHi = "साफ कैमरा",
                category = "ALL",
                anchor = "NONE",
                assetPath = "",
                iconAssetPath = ""
            )
        )

        try {
            context.assets.open("lenses/lenses_catalog.json").use { inputStream ->
                val reader = InputStreamReader(inputStream)
                val jsonStr = reader.readText()
                val jsonArray = JSONArray(jsonStr)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        LensItem(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            nameHi = obj.optString("name_hi", obj.getString("name")),
                            category = obj.getString("category"),
                            anchor = obj.getString("anchor"),
                            assetPath = obj.optString("asset", ""),
                            secondaryAssetPath = obj.optString("secondary_asset", ""),
                            iconAssetPath = obj.optString("icon", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cachedLenses = list
        return list
    }

    fun getCategories(): List<Pair<String, String>> {
        return listOf(
            "ALL" to "🔥 All (50)",
            "ANIMALS" to "🐶 Animals",
            "BEAUTY" to "✨ Beauty Glam",
            "FUNNY" to "😂 Funny & Warp",
            "GLASSES" to "🕶️ Glasses & Hats",
            "MAGIC" to "🪄 Magic & FX"
        )
    }
}
