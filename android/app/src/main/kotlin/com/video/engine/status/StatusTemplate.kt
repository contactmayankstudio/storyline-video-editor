package com.video.engine.status

data class StatusSlot(
    val slotId: Int,
    val durationMs: Long,
    val transitionOut: String = "crossfade"
)

data class StatusTextOverlay(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val posYPercent: Float = 0.25f,
    val scale: Float = 1.0f,
    val colorHex: String = "0xFFFFFFFF"
)

data class StatusTemplate(
    val id: String,
    val title: String,
    val category: String,
    val emoji: String,
    val durationMs: Long,
    val requiredPhotoCount: Int,
    val bpm: Int,
    val description: String,
    val gradientStartHex: String,
    val gradientEndHex: String,
    val audioAssetPath: String,
    val lutFilter: String,
    val slots: List<StatusSlot>,
    val textOverlays: List<StatusTextOverlay>
)

object StatusTemplateRepository {
    val allTemplates: List<StatusTemplate> = listOf(
        StatusTemplate(
            id = "tpl_tr_beat_drop",
            title = "Viral Bass Drop Reels",
            category = "Trending",
            emoji = "🔥",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 128,
            description = "High-energy fast cuts synced to heavy bass beats with flash and zoom transitions.",
            gradientStartHex = "#FF416C",
            gradientEndHex = "#8A2387",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "flash"),
                StatusSlot(2, 3000L, "zoom_in"),
                StatusSlot(3, 3000L, "whip_right"),
                StatusSlot(4, 3000L, "glitch")
            ),
            textOverlays = listOf(
                StatusTextOverlay("WAIT FOR THE DROP 🔥", 0L, 3000L, 0.22f, 1.25f, "0xFFFFE500"),
                StatusTextOverlay("BEAT SYNC ⚡", 6000L, 10000L, 0.82f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_cyberpunk_glitch",
            title = "Cyberpunk Neon Glitch",
            category = "Trending",
            emoji = "⚡",
            durationMs = 14000L,
            requiredPhotoCount = 5,
            bpm = 135,
            description = "Futuristic neon RGB split glitch montage with gaming sound effects.",
            gradientStartHex = "#00F2FE",
            gradientEndHex = "#4FACFE",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "cyberpunk_neon.cube",
            slots = listOf(
                StatusSlot(1, 2800L, "glitch"),
                StatusSlot(2, 2800L, "flash"),
                StatusSlot(3, 2800L, "wipe"),
                StatusSlot(4, 2800L, "slide"),
                StatusSlot(5, 2800L, "glitch")
            ),
            textOverlays = listOf(
                StatusTextOverlay("NEON VIBES 🌌", 500L, 4000L, 0.2f, 1.3f, "0xFF00E5FF"),
                StatusTextOverlay("CYBER REELS ⚡", 7000L, 13000L, 0.85f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_velocity_speed",
            title = "Velocity Smooth Flow",
            category = "Trending",
            emoji = "🚀",
            durationMs = 10000L,
            requiredPhotoCount = 4,
            bpm = 130,
            description = "Smooth speed-ramping flow with motion blur transitions.",
            gradientStartHex = "#11998E",
            gradientEndHex = "#38EF7D",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 2500L, "zoom_in"),
                StatusSlot(2, 2500L, "slide"),
                StatusSlot(3, 2500L, "whip_right"),
                StatusSlot(4, 2500L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("VELOCITY FLOW 🚀", 0L, 3500L, 0.25f, 1.2f, "0xFF38EF7D"),
                StatusTextOverlay("FEEL THE VIBE ✨", 5000L, 9500L, 0.8f, 1.0f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_3d_zoom_parallax",
            title = "3D Dynamic Zoom Parallax",
            category = "Trending",
            emoji = "🌀",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 125,
            description = "Immersive 3D depth zooms that pull the subject right out of the frame.",
            gradientStartHex = "#8E2DE2",
            gradientEndHex = "#4A00E0",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "zoom_in"),
                StatusSlot(2, 3000L, "crossfade"),
                StatusSlot(3, 3000L, "zoom_in"),
                StatusSlot(4, 3000L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("3D PARALLAX 🌀", 500L, 4000L, 0.22f, 1.25f, "0xFFE040FB"),
                StatusTextOverlay("NEXT LEVEL 🌟", 6000L, 11000L, 0.82f, 1.0f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_flip_transition",
            title = "3D Cube Flip Montage",
            category = "Trending",
            emoji = "🧊",
            durationMs = 11000L,
            requiredPhotoCount = 4,
            bpm = 120,
            description = "Clean geometrical cube rotation cuts perfectly timed on the beat.",
            gradientStartHex = "#FC466B",
            gradientEndHex = "#3F5EFB",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 2750L, "slide"),
                StatusSlot(2, 2750L, "wipe"),
                StatusSlot(3, 2750L, "slide"),
                StatusSlot(4, 2750L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("FLIP IT UP 🔄", 0L, 3500L, 0.25f, 1.2f, "0xFFFFD700"),
                StatusTextOverlay("TRENDING NOW 🔥", 5500L, 10500L, 0.8f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_cinematic_hype",
            title = "Cinematic Hype Intro",
            category = "Trending",
            emoji = "🎬",
            durationMs = 15000L,
            requiredPhotoCount = 5,
            bpm = 115,
            description = "Dramatic Hollywood-style widescreen trailer intro with letterbox styling.",
            gradientStartHex = "#232526",
            gradientEndHex = "#414345",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "fade"),
                StatusSlot(2, 3000L, "whip_right"),
                StatusSlot(3, 3000L, "crossfade"),
                StatusSlot(4, 3000L, "flash"),
                StatusSlot(5, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("PRESENTING 🎬", 500L, 4000L, 0.2f, 1.3f, "0xFF64B5F6"),
                StatusTextOverlay("THE BLOCKBUSTER 🔥", 7000L, 14000L, 0.85f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_rgb_split_bass",
            title = "RGB Split Bass Shake",
            category = "Trending",
            emoji = "💥",
            durationMs = 10000L,
            requiredPhotoCount = 3,
            bpm = 132,
            description = "Chromatic aberration shakes that bounce directly to the sub-bass kick.",
            gradientStartHex = "#FF0844",
            gradientEndHex = "#FFB199",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3333L, "flash"),
                StatusSlot(2, 3333L, "glitch"),
                StatusSlot(3, 3333L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("BASS BOOSTED 💥", 0L, 3500L, 0.22f, 1.3f, "0xFFFF1744"),
                StatusTextOverlay("SHAKE IT 🔥", 5000L, 9500L, 0.8f, 1.0f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_retro_vhs_tape",
            title = "90s Camcorder VHS Tape",
            category = "Trending",
            emoji = "📼",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 95,
            description = "Authentic retro tracking lines, static grain, and timestamp styling.",
            gradientStartHex = "#F7971E",
            gradientEndHex = "#FFD200",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "fade"),
                StatusSlot(2, 3000L, "crossfade"),
                StatusSlot(3, 3000L, "slide"),
                StatusSlot(4, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("REC ● 1998 📼", 500L, 4500L, 0.22f, 1.1f, "0xFF00E676"),
                StatusTextOverlay("VINTAGE GOLD 📻", 6000L, 11500L, 0.82f, 1.05f, "0xFFFFEA00")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_aesthetic_vlog",
            title = "Aesthetic Daily Vlog",
            category = "Trending",
            emoji = "☕",
            durationMs = 14000L,
            requiredPhotoCount = 5,
            bpm = 90,
            description = "Soft pastel tones, peaceful minimalist transitions, and clean typography.",
            gradientStartHex = "#E0C3FC",
            gradientEndHex = "#8EC5FC",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 2800L, "crossfade"),
                StatusSlot(2, 2800L, "fade"),
                StatusSlot(3, 2800L, "crossfade"),
                StatusSlot(4, 2800L, "slide"),
                StatusSlot(5, 2800L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("A LITTLE SLICE OF LIFE ☕", 500L, 5000L, 0.25f, 1.1f, "0xFFFFFFFF"),
                StatusTextOverlay("PEACEFUL MOMENTS 🌸", 6500L, 13000L, 0.8f, 1.0f, "0xFFF3E5F5")
            )
        ),
        StatusTemplate(
            id = "tpl_tr_flash_strobe",
            title = "Flash Strobe Party Cut",
            category = "Trending",
            emoji = "⚡",
            durationMs = 9000L,
            requiredPhotoCount = 3,
            bpm = 135,
            description = "High-octane club strobe flashes synced to peak bass hits.",
            gradientStartHex = "#F857A6",
            gradientEndHex = "#FF5858",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "flash"),
                StatusSlot(2, 3000L, "flash"),
                StatusSlot(3, 3000L, "whip_right")
            ),
            textOverlays = listOf(
                StatusTextOverlay("STROBE MODE ⚡", 0L, 3000L, 0.22f, 1.3f, "0xFFFFFF00"),
                StatusTextOverlay("TURN UP 🔊", 4500L, 8500L, 0.82f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_pehla_nasha",
            title = "Pehla Nasha Romantic Melodies",
            category = "Love",
            emoji = "❤️",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 95,
            description = "Dreamy acoustic guitar chords with soft warm film glow and lyrical poetry.",
            gradientStartHex = "#FF758C",
            gradientEndHex = "#FF7EB3",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "crossfade"),
                StatusSlot(2, 3500L, "fade"),
                StatusSlot(3, 3500L, "crossfade"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("TUM HI HO DIL MEIN ❤️", 500L, 5000L, 0.25f, 1.25f, "0xFFFF80AB"),
                StatusTextOverlay("FOREVER & ALWAYS 💍", 6000L, 13000L, 0.8f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_heartbeat_sync",
            title = "Heartbeat Pulse Love Story",
            category = "Love",
            emoji = "💓",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 88,
            description = "Gentle heartbeat zoom pulses with floating romantic heart aesthetics.",
            gradientStartHex = "#F80759",
            gradientEndHex = "#BC4E9C",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "zoom_in"),
                StatusSlot(2, 3000L, "crossfade"),
                StatusSlot(3, 3000L, "zoom_in"),
                StatusSlot(4, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("EVERY BEAT SAYS YOUR NAME 💓", 0L, 4500L, 0.22f, 1.2f, "0xFFFF4081"),
                StatusTextOverlay("TERA MERA RISHTA 🌹", 5500L, 11000L, 0.82f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_golden_sunset",
            title = "Sunset Couple Silhouette",
            category = "Love",
            emoji = "🌅",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 90,
            description = "Warm golden hour lens flares, amber sunset hues, and peaceful acoustic strums.",
            gradientStartHex = "#FF512F",
            gradientEndHex = "#F09819",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "fade"),
                StatusSlot(2, 3250L, "crossfade"),
                StatusSlot(3, 3250L, "fade"),
                StatusSlot(4, 3250L, "crossfade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("GOLDEN HOUR LOVE 🌅", 500L, 4500L, 0.25f, 1.2f, "0xFFFFD54F"),
                StatusTextOverlay("DHALTE SOORAJ KE SATH ☀️", 5500L, 12000L, 0.8f, 1.05f, "0xFFFFF8E1")
            )
        ),
        StatusTemplate(
            id = "tpl_love_two_souls",
            title = "Two Souls One Heart",
            category = "Love",
            emoji = "💞",
            durationMs = 15000L,
            requiredPhotoCount = 5,
            bpm = 85,
            description = "Deep emotional love status with romantic violin swells and soft slides.",
            gradientStartHex = "#EE9CA7",
            gradientEndHex = "#FFDDE1",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "crossfade"),
                StatusSlot(2, 3000L, "slide"),
                StatusSlot(3, 3000L, "fade"),
                StatusSlot(4, 3000L, "crossfade"),
                StatusSlot(5, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("DO DIL EK JAAN 💞", 500L, 5000L, 0.22f, 1.25f, "0xFFD81B60"),
                StatusTextOverlay("MERI DUNIYA HO TUM ✨", 6500L, 14000L, 0.82f, 1.1f, "0xFF4A148C")
            )
        ),
        StatusTemplate(
            id = "tpl_love_sweet_valentine",
            title = "Sweet Valentine Rose Petals",
            category = "Love",
            emoji = "🌹",
            durationMs = 11000L,
            requiredPhotoCount = 3,
            bpm = 92,
            description = "Falling rose petals with pink blush tones and sweet romantic quotes.",
            gradientStartHex = "#E55D87",
            gradientEndHex = "#5FC3E4",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3666L, "fade"),
                StatusSlot(2, 3666L, "crossfade"),
                StatusSlot(3, 3666L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("HAPPY VALENTINE 🌹", 0L, 4000L, 0.25f, 1.3f, "0xFFFF4081"),
                StatusTextOverlay("ONLY YOU FOREVER ❤️", 5000L, 10500L, 0.8f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_pehli_nazar",
            title = "Pehli Nazar Magic Sparks",
            category = "Love",
            emoji = "✨",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 94,
            description = "Glittering sparks and luminous glows capturing love at first sight.",
            gradientStartHex = "#D4FC79",
            gradientEndHex = "#96E6A1",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "crossfade"),
                StatusSlot(2, 3000L, "flash"),
                StatusSlot(3, 3000L, "crossfade"),
                StatusSlot(4, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("PEHLI NAZAR MEIN ✨", 500L, 4500L, 0.22f, 1.25f, "0xFF00E676"),
                StatusTextOverlay("JAADU CHHA GAYA 💖", 5500L, 11000L, 0.82f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_dil_ki_baat",
            title = "Dil Ki Baat Romantic Shayari",
            category = "Love",
            emoji = "💌",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 86,
            description = "Elegant handwritten lyrical script with soulful acoustic background.",
            gradientStartHex = "#FF6E7F",
            gradientEndHex = "#BFE9FF",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "slide"),
                StatusSlot(2, 3500L, "crossfade"),
                StatusSlot(3, 3500L, "slide"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("DIL KI HAR DUA MEIN 💌", 500L, 5000L, 0.25f, 1.2f, "0xFFFF4081"),
                StatusTextOverlay("BAS TERA HI NAAM HAI 🌹", 6000L, 13000L, 0.8f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_chand_sitare",
            title = "Chand Sitare Night Glow",
            category = "Love",
            emoji = "🌙",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 88,
            description = "Moonlit starry night aesthetic with gentle blue radiance and romantic ambiance.",
            gradientStartHex = "#2C3E50",
            gradientEndHex = "#FD746C",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "fade"),
                StatusSlot(2, 3250L, "crossfade"),
                StatusSlot(3, 3250L, "fade"),
                StatusSlot(4, 3250L, "crossfade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("CHAND SI SURAT TERI 🌙", 500L, 4500L, 0.22f, 1.2f, "0xFFFFF59D"),
                StatusTextOverlay("TAARON MEIN DHOONDU TUJHE ✨", 5500L, 12000L, 0.82f, 1.05f, "0xFFE0E0E0")
            )
        ),
        StatusTemplate(
            id = "tpl_love_wedding_vibes",
            title = "Royal Wedding Shehnai & Sangeet",
            category = "Love",
            emoji = "💍",
            durationMs = 15000L,
            requiredPhotoCount = 5,
            bpm = 100,
            description = "Opulent Indian wedding aesthetic with marigold garlands and golden shimmer.",
            gradientStartHex = "#F12711",
            gradientEndHex = "#F5AF19",
            audioAssetPath = "music_bgm/devotional_ambient_flute.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "crossfade"),
                StatusSlot(2, 3000L, "wipe"),
                StatusSlot(3, 3000L, "crossfade"),
                StatusSlot(4, 3000L, "slide"),
                StatusSlot(5, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("SHUBH VIVAH 💍✨", 500L, 5000L, 0.2f, 1.3f, "0xFFFFD700"),
                StatusTextOverlay("KUDIYAAN DI SANGEET 💃", 6500L, 14000L, 0.85f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_love_cute_couple_goal",
            title = "Cute Couple Goals Reels",
            category = "Love",
            emoji = "🥰",
            durationMs = 11000L,
            requiredPhotoCount = 4,
            bpm = 105,
            description = "Bubbly upbeat romantic reels cuts with playful emoji bounce animations.",
            gradientStartHex = "#FF9A8B",
            gradientEndHex = "#FF6A88",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 2750L, "zoom_in"),
                StatusSlot(2, 2750L, "slide"),
                StatusSlot(3, 2750L, "whip_right"),
                StatusSlot(4, 2750L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("MY FAVORITE HUMAN 🥰", 0L, 4000L, 0.25f, 1.25f, "0xFFFF4081"),
                StatusTextOverlay("COUPLE GOALS FOREVER 💖", 5000L, 10500L, 0.8f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_dil_tod_diya",
            title = "Dil Tod Diya Broken Heart",
            category = "Emotional",
            emoji = "💔",
            durationMs = 13000L,
            requiredPhotoCount = 3,
            bpm = 78,
            description = "Deep emotional piano with rain droplets and monochrome contrast.",
            gradientStartHex = "#2C3E50",
            gradientEndHex = "#000000",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 4333L, "fade"),
                StatusSlot(2, 4333L, "crossfade"),
                StatusSlot(3, 4333L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("DIL TOD DIYA TUNE 💔", 500L, 5000L, 0.25f, 1.25f, "0xFF90CAF9"),
                StatusTextOverlay("KABHI ALVIDA NA KEHNA 🌧️", 6000L, 12000L, 0.8f, 1.05f, "0xFFB0BEC5")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_lofi_late_night",
            title = "Late Night Lofi Tears",
            category = "Emotional",
            emoji = "🌧️",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 75,
            description = "Melancholic midnight rain window aesthetic with gentle lofi beats.",
            gradientStartHex = "#1F1C2C",
            gradientEndHex = "#928DAB",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "crossfade"),
                StatusSlot(2, 3500L, "fade"),
                StatusSlot(3, 3500L, "crossfade"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("RAAT KE SANNATE MEIN 🌧️", 500L, 5000L, 0.22f, 1.2f, "0xFF80DEEA"),
                StatusTextOverlay("YAADEIN BHI RONE LAGIN 🖤", 6000L, 13000L, 0.82f, 1.05f, "0xFFCFD8DC")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_tanha_safar",
            title = "Tanha Safar Lonely Road",
            category = "Emotional",
            emoji = "🚶",
            durationMs = 12000L,
            requiredPhotoCount = 3,
            bpm = 80,
            description = "Slow cinematic monochrome pan through an empty road of memories.",
            gradientStartHex = "#232526",
            gradientEndHex = "#414345",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "retro_monochrome_bw.cube",
            slots = listOf(
                StatusSlot(1, 4000L, "fade"),
                StatusSlot(2, 4000L, "fade"),
                StatusSlot(3, 4000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("TANHA CHAL RAHE HAIN 🚶", 0L, 4500L, 0.25f, 1.2f, "0xFFE0E0E0"),
                StatusTextOverlay("KOI SAATH NAHI 🍂", 5500L, 11000L, 0.8f, 1.0f, "0xFF9E9E9E")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_pain_in_eyes",
            title = "Pain In My Eyes Silent Sorrow",
            category = "Emotional",
            emoji = "🥺",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 82,
            description = "Heart-wrenching piano minor chords focusing on the sorrow behind a smile.",
            gradientStartHex = "#0F2027",
            gradientEndHex = "#203A43",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "crossfade"),
                StatusSlot(2, 3500L, "fade"),
                StatusSlot(3, 3500L, "crossfade"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("MUSKURAHAT KE PEECHE DARD 🥺", 500L, 5000L, 0.22f, 1.2f, "0xFFB0BEC5"),
                StatusTextOverlay("KASUR MERA HI THA 💔", 6000L, 13000L, 0.82f, 1.05f, "0xFF78909C")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_bikhre_khwab",
            title = "Bikhre Khwab Shattered Glass",
            category = "Emotional",
            emoji = "🥀",
            durationMs = 11000L,
            requiredPhotoCount = 3,
            bpm = 80,
            description = "Glass fracture glitch cuts reflecting shattered dreams and unkept promises.",
            gradientStartHex = "#3A1C71",
            gradientEndHex = "#D76D77",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3666L, "glitch"),
                StatusSlot(2, 3666L, "fade"),
                StatusSlot(3, 3666L, "glitch")
            ),
            textOverlays = listOf(
                StatusTextOverlay("KHWAB BIKHAR GAYE 🥀", 0L, 4000L, 0.25f, 1.25f, "0xFFEF9A9A"),
                StatusTextOverlay("WAADE TOOT GAYE 💔", 5000L, 10500L, 0.8f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_adhuri_kahani",
            title = "Adhuri Kahani Incomplete Story",
            category = "Emotional",
            emoji = "📖",
            durationMs = 15000L,
            requiredPhotoCount = 5,
            bpm = 76,
            description = "Poetic film slide reel tracing an unfinished chapter of love.",
            gradientStartHex = "#141E30",
            gradientEndHex = "#243B55",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "fade"),
                StatusSlot(2, 3000L, "crossfade"),
                StatusSlot(3, 3000L, "slide"),
                StatusSlot(4, 3000L, "fade"),
                StatusSlot(5, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("ADHURI REH GAYI KAHANI 📖", 500L, 5000L, 0.2f, 1.25f, "0xFF90CAF9"),
                StatusTextOverlay("DO KINAARE KABHI NAHI MILTE 🌊", 6500L, 14000L, 0.85f, 1.05f, "0xFFECEFF1")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_khamoshi",
            title = "Khamoshi Deep Silence",
            category = "Emotional",
            emoji = "🤫",
            durationMs = 12000L,
            requiredPhotoCount = 3,
            bpm = 72,
            description = "Dark ambient lofi chords expressing the quietness of a broken soul.",
            gradientStartHex = "#000428",
            gradientEndHex = "#004E92",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "retro_monochrome_bw.cube",
            slots = listOf(
                StatusSlot(1, 4000L, "crossfade"),
                StatusSlot(2, 4000L, "fade"),
                StatusSlot(3, 4000L, "crossfade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("KHAMOSHI BHI BOLTI HAI 🤫", 0L, 4500L, 0.25f, 1.2f, "0xFFB0BEC5"),
                StatusTextOverlay("JAB DIL ROTA HAI 🌧️", 5500L, 11500L, 0.8f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_sad_bewafa_memories",
            title = "Yaadon Ka Dhuwan Smoke Memories",
            category = "Emotional",
            emoji = "💨",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 78,
            description = "Wispy smoke dissolve transitions drifting through past memories.",
            gradientStartHex = "#434343",
            gradientEndHex = "#000000",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "fade"),
                StatusSlot(2, 3250L, "crossfade"),
                StatusSlot(3, 3250L, "fade"),
                StatusSlot(4, 3250L, "crossfade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("YAADON KA DHUWAN 💨", 500L, 4500L, 0.22f, 1.2f, "0xFFCFD8DC"),
                StatusTextOverlay("BHULA DIYA TUNE HUMEIN 🍂", 5500L, 12000L, 0.82f, 1.05f, "0xFF9E9E9E")
            )
        ),
        StatusTemplate(
            id = "tpl_att_royal_entry",
            title = "Royal Gangster Entry",
            category = "Attitude",
            emoji = "👑",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 130,
            description = "Heavy 808 sub bass drop with golden flare borders and slow-mo walk cuts.",
            gradientStartHex = "#F7971E",
            gradientEndHex = "#FFD200",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "whip_right"),
                StatusSlot(2, 3000L, "flash"),
                StatusSlot(3, 3000L, "zoom_in"),
                StatusSlot(4, 3000L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("APNA TIME AAYEGA NAHI 👑", 0L, 4000L, 0.22f, 1.3f, "0xFFFFD700"),
                StatusTextOverlay("APNA TIME HUM LAAYENGE 🔥", 5500L, 11000L, 0.82f, 1.15f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_att_baap_level",
            title = "Baap Level Swagger",
            category = "Attitude",
            emoji = "🕶️",
            durationMs = 10000L,
            requiredPhotoCount = 3,
            bpm = 135,
            description = "High-impact aggressive beat drops with flashing red alert neon.",
            gradientStartHex = "#ED213A",
            gradientEndHex = "#93291E",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3333L, "flash"),
                StatusSlot(2, 3333L, "glitch"),
                StatusSlot(3, 3333L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("HUMSE MAT ULJHO 🕶️", 0L, 3500L, 0.25f, 1.3f, "0xFFFF1744"),
                StatusTextOverlay("SHIKARI BHI HUM HAIN 🦁", 4500L, 9500L, 0.8f, 1.15f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_att_mafia_boss",
            title = "Mafia Boss Dark Throne",
            category = "Attitude",
            emoji = "🎩",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 125,
            description = "Dark moody cinematic noir with heavy trap beats and golden royalty accents.",
            gradientStartHex = "#000000",
            gradientEndHex = "#434343",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "fade"),
                StatusSlot(2, 3500L, "whip_right"),
                StatusSlot(3, 3500L, "zoom_in"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("RULES WE MAKE 🎩", 500L, 4500L, 0.22f, 1.25f, "0xFFFFC107"),
                StatusTextOverlay("THE GODFATHER VIBES 👑", 5500L, 13000L, 0.82f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_att_desi_swagger",
            title = "Desi Swagger Bullet Ride",
            category = "Attitude",
            emoji = "🏍️",
            durationMs = 11000L,
            requiredPhotoCount = 4,
            bpm = 128,
            description = "Rustic Punjabi / Haryanvi swag with bullet thumping rhythm and dust swirls.",
            gradientStartHex = "#FF8008",
            gradientEndHex = "#FFC837",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 2750L, "slide"),
                StatusSlot(2, 2750L, "flash"),
                StatusSlot(3, 2750L, "slide"),
                StatusSlot(4, 2750L, "whip_right")
            ),
            textOverlays = listOf(
                StatusTextOverlay("DESI SWAG BULLET RIDE 🏍️", 0L, 4000L, 0.25f, 1.25f, "0xFFFF9800"),
                StatusTextOverlay("TEVAR APNA DESI HAI 🦁", 5000L, 10500L, 0.8f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_att_killer_eyes",
            title = "Killer Eyes Zoom Focus",
            category = "Attitude",
            emoji = "👁️",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 130,
            description = "Intense close-up zooms on the eyes with electric lightning cuts.",
            gradientStartHex = "#4B1248",
            gradientEndHex = "#F0C27B",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "cyberpunk_neon.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "zoom_in"),
                StatusSlot(2, 3000L, "glitch"),
                StatusSlot(3, 3000L, "zoom_in"),
                StatusSlot(4, 3000L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("ANKHON MEIN AAG 👁️🔥", 500L, 4500L, 0.22f, 1.3f, "0xFFFFD700"),
                StatusTextOverlay("FEAR NO ONE ⚡", 5500L, 11000L, 0.82f, 1.15f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_att_gold_drip",
            title = "24K Gold Drip Luxury",
            category = "Attitude",
            emoji = "💎",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 126,
            description = "Golden luxury aesthetic with glitter sparkle transitions and rich aura.",
            gradientStartHex = "#FFE000",
            gradientEndHex = "#799F0C",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "crossfade"),
                StatusSlot(2, 3250L, "flash"),
                StatusSlot(3, 3250L, "whip_right"),
                StatusSlot(4, 3250L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("PURE GOLD DRIP 💎", 500L, 4500L, 0.25f, 1.25f, "0xFFFFEB3B"),
                StatusTextOverlay("BORN TO SHINE ✨", 5500L, 12000L, 0.8f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_lyr_urdu_shayari",
            title = "Urdu Nazm & Golden Shayari",
            category = "Emotional",
            emoji = "✍️",
            durationMs = 15000L,
            requiredPhotoCount = 4,
            bpm = 80,
            description = "Soulful Urdu poetry script floating gently over cinematic monochrome frames.",
            gradientStartHex = "#3E5151",
            gradientEndHex = "#DECBA4",
            audioAssetPath = "music_bgm/sad_piano_emotional.wav",
            lutFilter = "retro_monochrome_bw.cube",
            slots = listOf(
                StatusSlot(1, 3750L, "fade"),
                StatusSlot(2, 3750L, "crossfade"),
                StatusSlot(3, 3750L, "fade"),
                StatusSlot(4, 3750L, "crossfade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("EK SHAM KI KHAMOSHI MEIN ✍️", 500L, 5500L, 0.22f, 1.2f, "0xFFFFE082"),
                StatusTextOverlay("TERI YAAD KE SAYE HAIN 🍂", 6500L, 14000L, 0.82f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_lyr_neon_glow_lyrics",
            title = "Neon Glow Karaoke Lyrics",
            category = "Attitude",
            emoji = "💡",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 105,
            description = "Vibrant neon text glowing word-by-word with cyberpunk colors.",
            gradientStartHex = "#8A2387",
            gradientEndHex = "#E94057",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "cyberpunk_neon.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "glitch"),
                StatusSlot(2, 3250L, "crossfade"),
                StatusSlot(3, 3250L, "flash"),
                StatusSlot(4, 3250L, "slide")
            ),
            textOverlays = listOf(
                StatusTextOverlay("SING ALONG WITH ME 💡", 0L, 4500L, 0.25f, 1.25f, "0xFF00E5FF"),
                StatusTextOverlay("NEON NIGHTS FOREVER 🌌", 5500L, 12000L, 0.8f, 1.1f, "0xFFFF4081")
            )
        ),
        StatusTemplate(
            id = "tpl_lyr_hindi_kavita",
            title = "Hindi Kavita Virah Geet",
            category = "Emotional",
            emoji = "📜",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 84,
            description = "Classical Indian literary kavita with bansuri flute echoes and parchment style.",
            gradientStartHex = "#BA8B02",
            gradientEndHex = "#181818",
            audioAssetPath = "music_bgm/devotional_ambient_flute.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "crossfade"),
                StatusSlot(2, 3500L, "fade"),
                StatusSlot(3, 3500L, "crossfade"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("JEENE KI WOH RAHEIN 📜", 500L, 5000L, 0.22f, 1.2f, "0xFFFFD54F"),
                StatusTextOverlay("JAHAN TUM THI HUM THE 🌸", 6000L, 13000L, 0.82f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_lyr_paper_tear",
            title = "Paper Tear Scrapbook Lyrics",
            category = "Attitude",
            emoji = "📑",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 95,
            description = "Handmade paper ripped borders with retro polaroid photo slides.",
            gradientStartHex = "#E6DADA",
            gradientEndHex = "#274046",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "slide"),
                StatusSlot(2, 3000L, "wipe"),
                StatusSlot(3, 3000L, "slide"),
                StatusSlot(4, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("TORN PAGES OF LIFE 📑", 500L, 4500L, 0.25f, 1.2f, "0xFF37474F"),
                StatusTextOverlay("WRITTEN WITH LOVE ✏️", 5500L, 11000L, 0.8f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_lyr_golden_calligraphy",
            title = "Golden Calligraphy Luxury",
            category = "Attitude",
            emoji = "🖋️",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 88,
            description = "Embossed metallic gold cursive font radiating soft warm sparkles.",
            gradientStartHex = "#D4AF37",
            gradientEndHex = "#2F2F2F",
            audioAssetPath = "music_bgm/romantic_acoustic_love.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "crossfade"),
                StatusSlot(2, 3500L, "fade"),
                StatusSlot(3, 3500L, "crossfade"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("SUNAHRE ALFAAZ 🖋️", 500L, 5000L, 0.22f, 1.25f, "0xFFFFD700"),
                StatusTextOverlay("DIL SE NIKLE BOLEIN ✨", 6000L, 13000L, 0.82f, 1.05f, "0xFFFFF8E1")
            )
        ),
        StatusTemplate(
            id = "tpl_lyr_vintage_typewriter",
            title = "Vintage Typewriter Quotes",
            category = "Attitude",
            emoji = "⌨️",
            durationMs = 11000L,
            requiredPhotoCount = 3,
            bpm = 90,
            description = "Classic Courier typewriter typography with ink blotches and nostalgic film.",
            gradientStartHex = "#8E9EAB",
            gradientEndHex = "#EEF2F3",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "retro_monochrome_bw.cube",
            slots = listOf(
                StatusSlot(1, 3666L, "fade"),
                StatusSlot(2, 3666L, "slide"),
                StatusSlot(3, 3666L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("CLICK CLACK MEMORIES ⌨️", 0L, 4000L, 0.25f, 1.2f, "0xFF212121"),
                StatusTextOverlay("STORIES NEVER DIE 📖", 5000L, 10500L, 0.8f, 1.05f, "0xFF424242")
            )
        ),
        StatusTemplate(
            id = "tpl_bday_bash_sparkle",
            title = "Happy Birthday Confetti Bash",
            category = "Celebration",
            emoji = "🎂",
            durationMs = 11000L,
            requiredPhotoCount = 4,
            bpm = 120,
            description = "Bursting confetti fireworks, balloons, and sparkling golden birthday cheers.",
            gradientStartHex = "#FF6B6B",
            gradientEndHex = "#FFA07A",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 2750L, "flash"),
                StatusSlot(2, 2750L, "slide"),
                StatusSlot(3, 2750L, "wipe"),
                StatusSlot(4, 2750L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("HAPPY BIRTHDAY! 🎂🎉", 0L, 4000L, 0.2f, 1.3f, "0xFFFFD700"),
                StatusTextOverlay("WISHING YOU JOY & SUCCESS ✨", 5000L, 10500L, 0.8f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_bday_golden_glam",
            title = "Golden Glam Birthday Queen/King",
            category = "Celebration",
            emoji = "👑",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 115,
            description = "Champagne toasts, gold glitter streamers, and royal birthday banner aesthetic.",
            gradientStartHex = "#FFE259",
            gradientEndHex = "#FFA751",
            audioAssetPath = "music_bgm/attitude_bass_drop.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "crossfade"),
                StatusSlot(2, 3250L, "flash"),
                StatusSlot(3, 3250L, "zoom_in"),
                StatusSlot(4, 3250L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("CHEERS TO YOUR SPECIAL DAY 🥂", 500L, 4500L, 0.22f, 1.25f, "0xFFFFD700"),
                StatusTextOverlay("MAY ALL DREAMS COME TRUE 👑", 5500L, 12000L, 0.82f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_bday_party_disco",
            title = "Midnight Disco Cake & Candles",
            category = "Celebration",
            emoji = "🥳",
            durationMs = 10000L,
            requiredPhotoCount = 3,
            bpm = 128,
            description = "Candle blow-out slow-mo cut into energetic colorful disco party lights.",
            gradientStartHex = "#B92B27",
            gradientEndHex = "#1565C0",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3333L, "flash"),
                StatusSlot(2, 3333L, "glitch"),
                StatusSlot(3, 3333L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("BLOW THE CANDLES 🕯️🎂", 0L, 3500L, 0.25f, 1.25f, "0xFFFFEB3B"),
                StatusTextOverlay("PARTY ALL NIGHT 🥳🎉", 4500L, 9500L, 0.8f, 1.15f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_bday_sweet_sixteen",
            title = "Sweet Celebration Memories",
            category = "Celebration",
            emoji = "🎈",
            durationMs = 12000L,
            requiredPhotoCount = 4,
            bpm = 105,
            description = "Cute pastel balloon floating intro with sweet cheerful birthday wishes.",
            gradientStartHex = "#FF9A9E",
            gradientEndHex = "#FECFEF",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3000L, "slide"),
                StatusSlot(2, 3000L, "crossfade"),
                StatusSlot(3, 3000L, "slide"),
                StatusSlot(4, 3000L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("ANOTHER YEAR WISER 🎈", 500L, 4500L, 0.22f, 1.2f, "0xFFE91E63"),
                StatusTextOverlay("STAY BLESSED ALWAYS 💖", 5500L, 11500L, 0.82f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_frnd_yaari_dosti",
            title = "Yaari Dosti Tere Jaisa Yaar",
            category = "Celebration",
            emoji = "🤝",
            durationMs = 13000L,
            requiredPhotoCount = 4,
            bpm = 108,
            description = "Heartwarming brotherhood celebration with joyful memories and group photos.",
            gradientStartHex = "#00C9FF",
            gradientEndHex = "#92FE9D",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3250L, "slide"),
                StatusSlot(2, 3250L, "crossfade"),
                StatusSlot(3, 3250L, "slide"),
                StatusSlot(4, 3250L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("TERE JAISA YAAR KAHAN 🤝", 500L, 4500L, 0.22f, 1.25f, "0xFF00E5FF"),
                StatusTextOverlay("YAAR BHI TU DILDAAR BHI TU ❤️", 5500L, 12000L, 0.82f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_frnd_squad_goals",
            title = "Squad Goals Crazy Gang",
            category = "Celebration",
            emoji = "🔥",
            durationMs = 11000L,
            requiredPhotoCount = 4,
            bpm = 128,
            description = "Upbeat comedy reel highlighting the craziest moments with best friends.",
            gradientStartHex = "#F857A6",
            gradientEndHex = "#FF5858",
            audioAssetPath = "music_bgm/energetic_reels_trap_beat.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 2750L, "flash"),
                StatusSlot(2, 2750L, "whip_right"),
                StatusSlot(3, 2750L, "zoom_in"),
                StatusSlot(4, 2750L, "flash")
            ),
            textOverlays = listOf(
                StatusTextOverlay("CRAZY GANG SQUAD 🔥", 0L, 4000L, 0.25f, 1.3f, "0xFFFFD700"),
                StatusTextOverlay("WE RUN THIS TOWN 🚀", 5000L, 10500L, 0.8f, 1.1f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_frnd_college_days",
            title = "College Days Golden Nostalgia",
            category = "Celebration",
            emoji = "🎓",
            durationMs = 14000L,
            requiredPhotoCount = 5,
            bpm = 92,
            description = "Emotional campus farewell montage with warm nostalgic film filters.",
            gradientStartHex = "#4CA1AF",
            gradientEndHex = "#C4E0E5",
            audioAssetPath = "music_bgm/lofi_chill_vlog_beat.wav",
            lutFilter = "vintage_warm_70s.cube",
            slots = listOf(
                StatusSlot(1, 2800L, "crossfade"),
                StatusSlot(2, 2800L, "fade"),
                StatusSlot(3, 2800L, "slide"),
                StatusSlot(4, 2800L, "crossfade"),
                StatusSlot(5, 2800L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("WOH COLLEGE KE DIN 🎓", 500L, 5000L, 0.2f, 1.2f, "0xFF004D40"),
                StatusTextOverlay("YAADEIN JO HAMESHA RAHEIN 📸", 6500L, 13000L, 0.85f, 1.05f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_dev_mahadev_bhole",
            title = "Har Har Mahadev Bholenath",
            category = "Celebration",
            emoji = "🔱",
            durationMs = 15000L,
            requiredPhotoCount = 4,
            bpm = 90,
            description = "Powerful Shiva trident damru vibrations with ethereal mountain mists.",
            gradientStartHex = "#00416A",
            gradientEndHex = "#E4E5E6",
            audioAssetPath = "music_bgm/devotional_ambient_flute.wav",
            lutFilter = "moody_dark_film.cube",
            slots = listOf(
                StatusSlot(1, 3750L, "fade"),
                StatusSlot(2, 3750L, "crossfade"),
                StatusSlot(3, 3750L, "flash"),
                StatusSlot(4, 3750L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("HAR HAR MAHADEV 🔱🕉️", 500L, 5500L, 0.22f, 1.35f, "0xFFFFD700"),
                StatusTextOverlay("JAI BHOLENATH SHANKAR ⚡", 6500L, 14000L, 0.82f, 1.15f, "0xFFFFFFFF")
            )
        ),
        StatusTemplate(
            id = "tpl_dev_shree_ram",
            title = "Jai Shree Ram Ayodhya Dham",
            category = "Celebration",
            emoji = "🏹",
            durationMs = 14000L,
            requiredPhotoCount = 4,
            bpm = 95,
            description = "Saffron divine glow with temple bell chimes and magnificent golden radiance.",
            gradientStartHex = "#FF8008",
            gradientEndHex = "#FFC837",
            audioAssetPath = "music_bgm/devotional_ambient_flute.wav",
            lutFilter = "cinematic_teal_and_orange.cube",
            slots = listOf(
                StatusSlot(1, 3500L, "crossfade"),
                StatusSlot(2, 3500L, "wipe"),
                StatusSlot(3, 3500L, "crossfade"),
                StatusSlot(4, 3500L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("JAI SHREE RAM 🏹🚩", 500L, 5000L, 0.22f, 1.35f, "0xFFFF6F00"),
                StatusTextOverlay("RAM MANDIR AYODHYA DHAM 🛕", 6000L, 13000L, 0.82f, 1.1f, "0xFFFFF8E1")
            )
        ),
        StatusTemplate(
            id = "tpl_dev_radhe_krishna",
            title = "Radhe Krishna Divine Flute",
            category = "Celebration",
            emoji = "🪶",
            durationMs = 15000L,
            requiredPhotoCount = 4,
            bpm = 88,
            description = "Melodious Vrindavan bansuri with peacock feather colors and blissful peace.",
            gradientStartHex = "#134E5E",
            gradientEndHex = "#71B280",
            audioAssetPath = "music_bgm/devotional_ambient_flute.wav",
            lutFilter = "vibrant_pop_reels.cube",
            slots = listOf(
                StatusSlot(1, 3750L, "crossfade"),
                StatusSlot(2, 3750L, "fade"),
                StatusSlot(3, 3750L, "crossfade"),
                StatusSlot(4, 3750L, "fade")
            ),
            textOverlays = listOf(
                StatusTextOverlay("RADHE RADHE BOLO 🪶✨", 500L, 5000L, 0.22f, 1.3f, "0xFFFFEB3B"),
                StatusTextOverlay("SHREE KRISHNA GOVIND HARE 🌸", 6500L, 14000L, 0.82f, 1.1f, "0xFFFFFFFF")
            )
        )
    )

    val categories: List<String> = listOf("All", "Trending", "Love", "Attitude", "Emotional", "Celebration")

    fun getByCategory(category: String): List<StatusTemplate> {
        if (category.equals("All", ignoreCase = true)) return allTemplates
        return allTemplates.filter { it.category.equals(category, ignoreCase = true) }
    }
}
