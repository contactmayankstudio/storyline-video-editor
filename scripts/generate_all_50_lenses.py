#!/usr/bin/env python3
"""
Generate the full 50-lens catalog and distinct 128x128 preview badges.
"""

import os
import json
import math
from PIL import Image, ImageDraw, ImageFont

ASSETS_DIR = "/home/am/storyline/android/app/src/main/assets/lenses"
os.makedirs(ASSETS_DIR, exist_ok=True)

# 50 Filters Definition
LENSES = [
    # --- 1. CUTE ANIMALS (10) ---
    {
        "id": "lens_animal_puppy",
        "name": "Playful Puppy",
        "name_hi": "प्यारा पिल्ला",
        "category": "ANIMALS",
        "anchor": "PUPPY",
        "asset": "lenses/prop_puppy_ears.png",
        "secondary_asset": "lenses/prop_puppy_nose.png",
        "icon_bg": "#FF9933",
        "icon_label": "🐶"
    },
    {
        "id": "lens_animal_bunny",
        "name": "Cute Bunny",
        "name_hi": "क्यूट खरगोश",
        "category": "ANIMALS",
        "anchor": "BUNNY",
        "asset": "lenses/prop_bunny_ears.png",
        "secondary_asset": "lenses/prop_bunny_nose.png",
        "icon_bg": "#FF8DA1",
        "icon_label": "🐰"
    },
    {
        "id": "lens_animal_kitty",
        "name": "Sweet Kitty",
        "name_hi": "स्वीट बिल्ली",
        "category": "ANIMALS",
        "anchor": "KITTY",
        "asset": "lenses/prop_kitty_ears.png",
        "secondary_asset": "lenses/prop_kitty_whiskers.png",
        "icon_bg": "#FFB6C1",
        "icon_label": "🐱"
    },
    {
        "id": "lens_animal_bear",
        "name": "Teddy Bear",
        "name_hi": "टेडी बियर",
        "category": "ANIMALS",
        "anchor": "BEAR",
        "asset": "lenses/prop_bear_ears.png",
        "secondary_asset": "lenses/prop_bear_snout.png",
        "icon_bg": "#8B5A2B",
        "icon_label": "🐻"
    },
    {
        "id": "lens_animal_panda",
        "name": "Panda Hug",
        "name_hi": "पांडा हग",
        "category": "ANIMALS",
        "anchor": "PANDA",
        "asset": "lenses/prop_panda_ears.png",
        "secondary_asset": "",
        "icon_bg": "#333333",
        "icon_label": "🐼"
    },
    {
        "id": "lens_animal_fox",
        "name": "Mystic Fox",
        "name_hi": "जादुई लोमड़ी",
        "category": "ANIMALS",
        "anchor": "FOX",
        "asset": "lenses/prop_fox_ears.png",
        "secondary_asset": "",
        "icon_bg": "#E65100",
        "icon_label": "🦊"
    },
    {
        "id": "lens_animal_tiger",
        "name": "Wild Tiger",
        "name_hi": "शेर खान",
        "category": "ANIMALS",
        "anchor": "TIGER",
        "asset": "lenses/prop_tiger_ears.png",
        "secondary_asset": "",
        "icon_bg": "#FF6F00",
        "icon_label": "🐯"
    },
    {
        "id": "lens_animal_lion",
        "name": "Lion King",
        "name_hi": "सिंह राजा",
        "category": "ANIMALS",
        "anchor": "LION",
        "asset": "lenses/prop_lion_mane.png",
        "secondary_asset": "",
        "icon_bg": "#FFB300",
        "icon_label": "🦁"
    },
    {
        "id": "lens_animal_hamster",
        "name": "Chubby Hamster",
        "name_hi": "क्यूट हैमस्टर",
        "category": "ANIMALS",
        "anchor": "HAMSTER",
        "asset": "lenses/prop_bear_ears.png",
        "secondary_asset": "",
        "icon_bg": "#D7CCC8",
        "icon_label": "🐹"
    },
    {
        "id": "lens_animal_unicorn",
        "name": "Magic Unicorn",
        "name_hi": "जादुई यूनिकॉर्न",
        "category": "ANIMALS",
        "anchor": "UNICORN",
        "asset": "lenses/prop_unicorn_horn.png",
        "secondary_asset": "",
        "icon_bg": "#9C27B0",
        "icon_label": "🦄"
    },

    # --- 2. BEAUTY & AESTHETIC (10) ---
    {
        "id": "lens_beauty_peach",
        "name": "Peach Glow",
        "name_hi": "आड़ू ग्लो",
        "category": "BEAUTY",
        "anchor": "BEAUTY_PEACH",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FF8A65",
        "icon_label": "🍑"
    },
    {
        "id": "lens_beauty_porcelain",
        "name": "Porcelain Glam",
        "name_hi": "सिल्क स्किन",
        "category": "BEAUTY",
        "anchor": "BEAUTY_PORCELAIN",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#F8BBD0",
        "icon_label": "✨"
    },
    {
        "id": "lens_beauty_golden",
        "name": "Golden Hour",
        "name_hi": "गोल्डन ऑवर",
        "category": "BEAUTY",
        "anchor": "BEAUTY_GOLDEN",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FFA000",
        "icon_label": "🌅"
    },
    {
        "id": "lens_beauty_rose",
        "name": "Rosy Blush",
        "name_hi": "गुलाबी रंगत",
        "category": "BEAUTY",
        "anchor": "BEAUTY_ROSE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#E91E63",
        "icon_label": "🌸"
    },
    {
        "id": "lens_beauty_film35",
        "name": "35mm Vintage Film",
        "name_hi": "विंटेज फिल्म",
        "category": "BEAUTY",
        "anchor": "BEAUTY_FILM35",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#795548",
        "icon_label": "🎞️"
    },
    {
        "id": "lens_beauty_tealorange",
        "name": "Teal & Orange",
        "name_hi": "टील ऑरेंज",
        "category": "BEAUTY",
        "anchor": "BEAUTY_TEALORANGE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#00897B",
        "icon_label": "🎬"
    },
    {
        "id": "lens_beauty_glass",
        "name": "Glass Skin",
        "name_hi": "ग्लास स्किन",
        "category": "BEAUTY",
        "anchor": "BEAUTY_GLASS",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#80DEEA",
        "icon_label": "💎"
    },
    {
        "id": "lens_beauty_freckles",
        "name": "Sun Freckles",
        "name_hi": "सन फ्रेकल्स",
        "category": "BEAUTY",
        "anchor": "BEAUTY_FRECKLES",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FFB74D",
        "icon_label": "☀️"
    },
    {
        "id": "lens_beauty_butterflies",
        "name": "Butterfly Glow",
        "name_hi": "तितली ग्लो",
        "category": "BEAUTY",
        "anchor": "BEAUTY_BUTTERFLY",
        "asset": "lenses/prop_beauty_butterflies.png",
        "secondary_asset": "",
        "icon_bg": "#CE93D8",
        "icon_label": "🦋"
    },
    {
        "id": "lens_beauty_noir",
        "name": "Cinema Noir B&W",
        "name_hi": "ब्लैक एंड व्हाइट",
        "category": "BEAUTY",
        "anchor": "BEAUTY_NOIR",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#212121",
        "icon_label": "🖤"
    },

    # --- 3. FUNNY FACE & MESH WARP (10) - Real drawBitmapMesh Warping ---
    {
        "id": "lens_warp_chubby",
        "name": "Chubby Baby Cheeks",
        "name_hi": "मोटे फूले गाल",
        "category": "FUNNY",
        "anchor": "WARP_CHUBBY",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FF80AB",
        "icon_label": "👶"
    },
    {
        "id": "lens_warp_crooked",
        "name": "Crooked Meme Face",
        "name_hi": "टेढ़ा फनी चेहरा",
        "category": "FUNNY",
        "anchor": "WARP_CROOKED",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FFD600",
        "icon_label": "😏"
    },
    {
        "id": "lens_warp_pignose",
        "name": "Big Pig Snout",
        "name_hi": "बड़ा सूअर नाक",
        "category": "FUNNY",
        "anchor": "WARP_PIGNOSE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#F48FB1",
        "icon_label": "🐷"
    },
    {
        "id": "lens_warp_caveman",
        "name": "Caveman Long Jaw",
        "name_hi": "आदिमानव जबड़ा",
        "category": "FUNNY",
        "anchor": "WARP_CAVEMAN",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#6D4C41",
        "icon_label": "🗿"
    },
    {
        "id": "lens_warp_alien",
        "name": "Alien Big Eyes",
        "name_hi": "बड़ी एलियन आंखें",
        "category": "FUNNY",
        "anchor": "WARP_ALIEN",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#00E676",
        "icon_label": "👽"
    },
    {
        "id": "lens_warp_megaphone",
        "name": "Mega Mouth",
        "name_hi": "विशाल मुंह",
        "category": "FUNNY",
        "anchor": "WARP_MEGAMOUTH",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FF5722",
        "icon_label": "😲"
    },
    {
        "id": "lens_warp_tinyface",
        "name": "Tiny Squeezed Face",
        "name_hi": "छोटू चेहरा",
        "category": "FUNNY",
        "anchor": "WARP_TINYFACE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#7C4DFF",
        "icon_label": "🤏"
    },
    {
        "id": "lens_warp_swirl",
        "name": "Vortex Swirl",
        "name_hi": "भंवर चक्र",
        "category": "FUNNY",
        "anchor": "WARP_SWIRL",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#00B0FF",
        "icon_label": "🌀"
    },
    {
        "id": "lens_warp_square",
        "name": "Block / Box Head",
        "name_hi": "चौकोर सिर",
        "category": "FUNNY",
        "anchor": "WARP_SQUARE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#8D6E63",
        "icon_label": "📦"
    },
    {
        "id": "lens_warp_ripple",
        "name": "Jello Wave Ripple",
        "name_hi": "लहरदार जेली",
        "category": "FUNNY",
        "anchor": "WARP_RIPPLE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#26C6DA",
        "icon_label": "〰️"
    },

    # --- 4. GLASSES & ACCESSORIES (10) ---
    {
        "id": "lens_glasses_idontcare",
        "name": "I Don't Care Shades",
        "name_hi": "आई डोंट केयर चश्मा",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_idontcare.png",
        "secondary_asset": "",
        "icon_bg": "#212121",
        "icon_label": "🕶️"
    },
    {
        "id": "lens_glasses_cyberpunk",
        "name": "Cyberpunk Visor",
        "name_hi": "साइबर नियॉन वाइज़र",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_cyberpunk.png",
        "secondary_asset": "",
        "icon_bg": "#00E5FF",
        "icon_label": "🥽"
    },
    {
        "id": "lens_glasses_aviator",
        "name": "Golden Aviators",
        "name_hi": "गोल्डन एविएटर",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_aviator.png",
        "secondary_asset": "",
        "icon_bg": "#FFD700",
        "icon_label": "🕶️"
    },
    {
        "id": "lens_glasses_heart",
        "name": "Cherry Heart Shades",
        "name_hi": "दिल वाला चश्मा",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_heart.png",
        "secondary_asset": "",
        "icon_bg": "#FF1744",
        "icon_label": "❤️"
    },
    {
        "id": "lens_glasses_round",
        "name": "Vintage Round Specs",
        "name_hi": "गोल चश्मा",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_aviator.png",
        "secondary_asset": "",
        "icon_bg": "#78909C",
        "icon_label": "👓"
    },
    {
        "id": "lens_glasses_thug",
        "name": "Thug Life Shades",
        "name_hi": "ठग लाइफ चश्मा",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_thug.png",
        "secondary_asset": "",
        "icon_bg": "#000000",
        "icon_label": "😎"
    },
    {
        "id": "lens_headwear_crown",
        "name": "Royal Gold Crown",
        "name_hi": "शाही मुकुट",
        "category": "GLASSES",
        "anchor": "HEAD",
        "asset": "lenses/prop_headwear_crown.png",
        "secondary_asset": "",
        "icon_bg": "#FFC107",
        "icon_label": "👑"
    },
    {
        "id": "lens_glasses_stardiamond",
        "name": "Diamond Star Glasses",
        "name_hi": "स्टार चश्मा",
        "category": "GLASSES",
        "anchor": "EYES",
        "asset": "lenses/prop_glasses_heart.png",
        "secondary_asset": "",
        "icon_bg": "#E040FB",
        "icon_label": "⭐"
    },
    {
        "id": "lens_headwear_flowercrown",
        "name": "Boho Flower Crown",
        "name_hi": "फूलों का मुकुट",
        "category": "GLASSES",
        "anchor": "HEAD",
        "asset": "lenses/prop_beauty_butterflies.png",
        "secondary_asset": "",
        "icon_bg": "#81C784",
        "icon_label": "🌸"
    },
    {
        "id": "lens_headwear_steampunk",
        "name": "Steampunk Goggles",
        "name_hi": "स्टीमपंक गॉगल्स",
        "category": "GLASSES",
        "anchor": "HEAD",
        "asset": "lenses/prop_glasses_cyberpunk.png",
        "secondary_asset": "",
        "icon_bg": "#A1887F",
        "icon_label": "⚙️"
    },

    # --- 5. MAGIC & FANTASY (10) ---
    {
        "id": "lens_magic_rainbow",
        "name": "Rainbow Mouth Fountain",
        "name_hi": "इंद्रधनुष फव्वारा",
        "category": "MAGIC",
        "anchor": "MAGIC_RAINBOW",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FF4081",
        "icon_label": "🌈"
    },
    {
        "id": "lens_magic_angel",
        "name": "Angel Halo & Hearts",
        "name_hi": "फरिश्ता प्रभामंडल",
        "category": "MAGIC",
        "anchor": "HEAD",
        "asset": "lenses/prop_magic_halo.png",
        "secondary_asset": "",
        "icon_bg": "#FFF176",
        "icon_label": "😇"
    },
    {
        "id": "lens_magic_firedemon",
        "name": "Fire Demon Breath",
        "name_hi": "ज्वाला दैत्य",
        "category": "MAGIC",
        "anchor": "HEAD",
        "asset": "lenses/prop_magic_horns.png",
        "secondary_asset": "",
        "icon_bg": "#D50000",
        "icon_label": "🔥"
    },
    {
        "id": "lens_magic_ghost",
        "name": "Ghost Phantom Aura",
        "name_hi": "भूतिया आत्मा",
        "category": "MAGIC",
        "anchor": "MAGIC_GHOST",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#80D8FF",
        "icon_label": "👻"
    },
    {
        "id": "lens_magic_galaxy",
        "name": "Cosmic Galaxy & Moon",
        "name_hi": "ब्रह्मांड व चांद",
        "category": "MAGIC",
        "anchor": "MAGIC_GALAXY",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#3F51B5",
        "icon_label": "🌌"
    },
    {
        "id": "lens_magic_glitter",
        "name": "Golden Dust Shower",
        "name_hi": "सुनहरी चमक",
        "category": "MAGIC",
        "anchor": "MAGIC_GLITTER",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#FFD54F",
        "icon_label": "✨"
    },
    {
        "id": "lens_magic_lightning",
        "name": "Electric Lightning Storm",
        "name_hi": "बिजली का तूफान",
        "category": "MAGIC",
        "anchor": "MAGIC_LIGHTNING",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#2979FF",
        "icon_label": "⚡"
    },
    {
        "id": "lens_magic_icequeen",
        "name": "Ice Frost Queen",
        "name_hi": "बर्फ की रानी",
        "category": "MAGIC",
        "anchor": "MAGIC_ICE",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#E0F7FA",
        "icon_label": "❄️"
    },
    {
        "id": "lens_magic_vhs90s",
        "name": "90s VHS Camcorder",
        "name_hi": "90s वीएचएस कैमरा",
        "category": "MAGIC",
        "anchor": "MAGIC_VHS",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#424242",
        "icon_label": "📼"
    },
    {
        "id": "lens_magic_neonhud",
        "name": "Sci-Fi Target Reticle",
        "name_hi": "साइबर टारगेट लॉक",
        "category": "MAGIC",
        "anchor": "MAGIC_NEONHUD",
        "asset": "",
        "secondary_asset": "",
        "icon_bg": "#00E676",
        "icon_label": "🎯"
    }
]

# Generate circular 128x128 preview icons
for lens in LENSES:
    icon_filename = f"icon_{lens['id']}.png"
    icon_path = os.path.join(ASSETS_DIR, icon_filename)
    lens["icon"] = f"lenses/{icon_filename}"

    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Outer circle border
    hex_col = lens["icon_bg"].lstrip("#")
    r, g, b = tuple(int(hex_col[i:i+2], 16) for i in (0, 2, 4))
    d.ellipse([4, 4, 124, 124], fill=(r, g, b, 235), outline=(255, 255, 255, 200), width=3)
    d.ellipse([10, 10, 118, 118], fill=(int(r*0.8), int(g*0.8), int(b*0.8), 245))

    # Center icon emoji/label
    emoji = lens["icon_label"]
    d.text((44, 40), emoji, fill=(255, 255, 255, 255))

    img.save(icon_path)

# Save lenses_catalog.json
catalog_path = os.path.join(ASSETS_DIR, "lenses_catalog.json")
with open(catalog_path, "w", encoding="utf-8") as f:
    json.dump(LENSES, f, indent=2, ensure_ascii=False)

print(f"Generated {len(LENSES)} lenses in {catalog_path}")
