#!/usr/bin/env python3
"""
Generate high-resolution transparent PNG assets and preview badges
for 50 Snapchat-style camera filters in Storyline.
"""

import os
import math
import json
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ASSETS_DIR = "/home/am/storyline/android/app/src/main/assets/lenses"
os.makedirs(ASSETS_DIR, exist_ok=True)

def draw_radial_gradient(draw, center, radius, inner_color, outer_color):
    cx, cy = center
    for r in range(radius, 0, -2):
        ratio = r / float(radius)
        c = (
            int(inner_color[0] * (1 - ratio) + outer_color[0] * ratio),
            int(inner_color[1] * (1 - ratio) + outer_color[1] * ratio),
            int(inner_color[2] * (1 - ratio) + outer_color[2] * ratio),
            int(inner_color[3] * (1 - ratio) + outer_color[3] * ratio)
        )
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)

# 1. Puppy Ears
def gen_puppy_ears():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Left Ear (Floppy, curved downward)
    left_ear = [(140, 20), (60, 40), (30, 120), (50, 230), (100, 240), (130, 190), (150, 100)]
    d.polygon(left_ear, fill=(139, 90, 43, 255))
    d.polygon([(x+8, y+8) for x, y in left_ear[:4]] + [(90, 200), (120, 140)], fill=(110, 70, 30, 255))
    # Right Ear
    right_ear = [(372, 20), (452, 40), (482, 120), (462, 230), (412, 240), (382, 190), (362, 100)]
    d.polygon(right_ear, fill=(139, 90, 43, 255))
    d.polygon([(x-8, y+8) for x, y in right_ear[:4]] + [(422, 200), (392, 140)], fill=(110, 70, 30, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_puppy_ears.png"))

def gen_puppy_nose():
    img = Image.new("RGBA", (256, 180), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Cute dog nose
    d.polygon([(50, 40), (206, 40), (150, 120), (128, 130), (106, 120)], fill=(30, 30, 35, 255))
    d.ellipse([70, 42, 186, 75], fill=(45, 45, 50, 255))
    # Nostrils
    d.ellipse([75, 70, 105, 95], fill=(15, 15, 18, 255))
    d.ellipse([151, 70, 181, 95], fill=(15, 15, 18, 255))
    # Specular shine
    d.ellipse([90, 46, 125, 58], fill=(220, 220, 240, 180))
    img.save(os.path.join(ASSETS_DIR, "prop_puppy_nose.png"))

# 2. Bunny Ears & Nose
def gen_bunny():
    img = Image.new("RGBA", (512, 384), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Left Ear (tall white, pink inside)
    d.ellipse([70, 20, 180, 360], fill=(250, 250, 255, 255), outline=(225, 225, 235, 255), width=3)
    d.ellipse([95, 60, 155, 330], fill=(255, 182, 193, 255))
    # Right Ear (slightly tilted)
    d.ellipse([332, 20, 442, 360], fill=(250, 250, 255, 255), outline=(225, 225, 235, 255), width=3)
    d.ellipse([357, 60, 417, 330], fill=(255, 182, 193, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_bunny_ears.png"))

    # Bunny nose
    nose = Image.new("RGBA", (256, 160), (0, 0, 0, 0))
    nd = ImageDraw.Draw(nose)
    nd.polygon([(100, 50), (156, 50), (128, 85)], fill=(255, 140, 165, 255))
    # Whiskers
    nd.line([(30, 60), (95, 68)], fill=(255, 255, 255, 200), width=3)
    nd.line([(25, 78), (95, 78)], fill=(255, 255, 255, 200), width=3)
    nd.line([(35, 96), (95, 86)], fill=(255, 255, 255, 200), width=3)
    nd.line([(226, 60), (161, 68)], fill=(255, 255, 255, 200), width=3)
    nd.line([(231, 78), (161, 78)], fill=(255, 255, 255, 200), width=3)
    nd.line([(221, 96), (161, 86)], fill=(255, 255, 255, 200), width=3)
    nose.save(os.path.join(ASSETS_DIR, "prop_bunny_nose.png"))

# 3. Kitty Ears & Whiskers
def gen_kitty():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Left triangular ear
    d.polygon([(60, 220), (130, 20), (220, 180)], fill=(40, 40, 45, 255))
    d.polygon([(90, 190), (135, 55), (195, 165)], fill=(255, 150, 180, 255))
    # Right triangular ear
    d.polygon([(452, 220), (382, 20), (292, 180)], fill=(40, 40, 45, 255))
    d.polygon([(422, 190), (377, 55), (317, 165)], fill=(255, 150, 180, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_kitty_ears.png"))

    nose = Image.new("RGBA", (256, 160), (0, 0, 0, 0))
    nd = ImageDraw.Draw(nose)
    nd.polygon([(110, 50), (146, 50), (128, 75)], fill=(255, 120, 160, 255))
    nd.line([(20, 50), (105, 62)], fill=(255, 255, 255, 220), width=3)
    nd.line([(15, 75), (105, 72)], fill=(255, 255, 255, 220), width=3)
    nd.line([(236, 50), (151, 62)], fill=(255, 255, 255, 220), width=3)
    nd.line([(241, 75), (151, 72)], fill=(255, 255, 255, 220), width=3)
    nose.save(os.path.join(ASSETS_DIR, "prop_kitty_whiskers.png"))

# 4. Bear Ears & Muzzle
def gen_bear():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Round brown fuzzy ears
    d.ellipse([60, 30, 190, 160], fill=(130, 82, 40, 255))
    d.ellipse([85, 55, 165, 135], fill=(205, 150, 95, 255))
    d.ellipse([322, 30, 452, 160], fill=(130, 82, 40, 255))
    d.ellipse([347, 55, 427, 135], fill=(205, 150, 95, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_bear_ears.png"))

    muzzle = Image.new("RGBA", (256, 180), (0, 0, 0, 0))
    md = ImageDraw.Draw(muzzle)
    md.ellipse([68, 30, 188, 120], fill=(225, 185, 135, 255))
    md.polygon([(110, 48), (146, 48), (128, 70)], fill=(40, 25, 15, 255))
    md.line([(128, 70), (128, 92)], fill=(40, 25, 15, 255), width=3)
    md.arc([110, 78, 128, 102], 0, 180, fill=(40, 25, 15, 255), width=3)
    md.arc([128, 78, 146, 102], 0, 180, fill=(40, 25, 15, 255), width=3)
    muzzle.save(os.path.join(ASSETS_DIR, "prop_bear_snout.png"))

# 5. Panda Ears & Eye Patches
def gen_panda():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([70, 30, 190, 150], fill=(25, 25, 30, 255))
    d.ellipse([322, 30, 442, 150], fill=(25, 25, 30, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_panda_ears.png"))

# 6. Fox Ears
def gen_fox():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon([(50, 220), (120, 20), (220, 180)], fill=(220, 90, 25, 255))
    d.polygon([(80, 190), (125, 50), (190, 160)], fill=(255, 250, 240, 255))
    d.polygon([(462, 220), (392, 20), (292, 180)], fill=(220, 90, 25, 255))
    d.polygon([(432, 190), (387, 50), (322, 160)], fill=(255, 250, 240, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_fox_ears.png"))

# 7. Tiger Ears
def gen_tiger():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([70, 30, 190, 150], fill=(235, 130, 30, 255))
    d.ellipse([90, 50, 170, 130], fill=(255, 220, 180, 255))
    d.line([(85, 45), (105, 80)], fill=(20, 20, 20, 255), width=6)
    d.line([(175, 45), (155, 80)], fill=(20, 20, 20, 255), width=6)
    d.ellipse([322, 30, 442, 150], fill=(235, 130, 30, 255))
    d.ellipse([342, 50, 422, 130], fill=(255, 220, 180, 255))
    d.line([(337, 45), (357, 80)], fill=(20, 20, 20, 255), width=6)
    d.line([(427, 45), (407, 80)], fill=(20, 20, 20, 255), width=6)
    img.save(os.path.join(ASSETS_DIR, "prop_tiger_ears.png"))

# 8. Lion Mane
def gen_lion():
    img = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Fluffy golden mane tufts in a ring
    center = (256, 256)
    for i in range(24):
        angle = i * (2 * math.pi / 24)
        x = 256 + 180 * math.cos(angle)
        y = 256 + 180 * math.sin(angle)
        d.ellipse([x-45, y-45, x+45, y+45], fill=(210, 130, 30, 210))
    for i in range(24):
        angle = (i + 0.5) * (2 * math.pi / 24)
        x = 256 + 195 * math.cos(angle)
        y = 256 + 195 * math.sin(angle)
        d.ellipse([x-35, y-35, x+35, y+35], fill=(240, 170, 50, 230))
    # Clear middle for face
    # Pillow doesn't do hole erasing easily on same canvas, so we compose with transparent center
    mask = Image.new("L", (512, 512), 255)
    md = ImageDraw.Draw(mask)
    md.ellipse([100, 100, 412, 412], fill=0)
    img.putalpha(mask)
    img.save(os.path.join(ASSETS_DIR, "prop_lion_mane.png"))

# 9. Unicorn Horn
def gen_unicorn():
    img = Image.new("RGBA", (256, 384), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Spiral Horn
    d.polygon([(128, 20), (95, 340), (161, 340)], fill=(255, 235, 170, 255))
    for y in range(40, 330, 35):
        ratio = y / 340.0
        w = int(15 + 40 * ratio)
        d.arc([128 - w, y, 128 + w, y + 25], 0, 180, fill=(255, 160, 200, 255), width=6)
    # Sparkle stars
    d.ellipse([118, 10, 138, 30], fill=(255, 255, 255, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_unicorn_horn.png"))

# 10. "I DON'T CARE" Black Sunglasses (User Screenshot 3)
def gen_idontcare_shades():
    img = Image.new("RGBA", (512, 200), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Bridge
    d.rectangle([230, 80, 282, 100], fill=(20, 20, 25, 255))
    # Left Frame (rectangular, rounded corners)
    d.rounded_rectangle([30, 40, 230, 150], radius=28, fill=(20, 20, 25, 255))
    d.rounded_rectangle([40, 50, 220, 140], radius=22, fill=(10, 10, 12, 255))
    # Right Frame
    d.rounded_rectangle([282, 40, 482, 150], radius=28, fill=(20, 20, 25, 255))
    d.rounded_rectangle([292, 50, 472, 140], radius=22, fill=(10, 10, 12, 255))
    # Text on lenses: "I don't" on left, "Care" on right
    # Use default bitmap font with large bold custom drawing
    d.text((75, 78), "I  don't", fill=(255, 255, 255, 255))
    d.text((345, 78), "Care", fill=(255, 255, 255, 255))
    # Specular shine
    d.line([(45, 55), (120, 55)], fill=(120, 120, 140, 120), width=3)
    d.line([(297, 55), (370, 55)], fill=(120, 120, 140, 120), width=3)
    img.save(os.path.join(ASSETS_DIR, "prop_glasses_idontcare.png"))

# 11. Cyberpunk Visor
def gen_cyberpunk_visor():
    img = Image.new("RGBA", (512, 180), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Futuristic sleek curved visor
    d.rounded_rectangle([40, 40, 472, 130], radius=24, fill=(0, 230, 255, 140), outline=(0, 255, 240, 255), width=4)
    # Hex/HUD accents
    d.line([(60, 40), (452, 40)], fill=(255, 0, 128, 200), width=3)
    d.line([(80, 85), (200, 85)], fill=(255, 255, 255, 180), width=2)
    d.line([(312, 85), (432, 85)], fill=(255, 255, 255, 180), width=2)
    d.text((220, 78), "[ SYS ACTIVE ]", fill=(0, 255, 255, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_glasses_cyberpunk.png"))

# 12. Golden Aviators
def gen_aviators():
    img = Image.new("RGBA", (512, 200), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Gold brow bar
    d.line([(100, 45), (412, 45)], fill=(230, 180, 30, 255), width=4)
    d.line([(220, 60), (292, 60)], fill=(230, 180, 30, 255), width=4)
    # Teardrop lenses
    d.rounded_rectangle([45, 45, 235, 165], radius=35, fill=(40, 55, 75, 180), outline=(245, 200, 40, 255), width=5)
    d.rounded_rectangle([277, 45, 467, 165], radius=35, fill=(40, 55, 75, 180), outline=(245, 200, 40, 255), width=5)
    # Sky reflections
    d.line([(65, 65), (215, 145)], fill=(255, 255, 255, 80), width=8)
    d.line([(297, 65), (447, 145)], fill=(255, 255, 255, 80), width=8)
    img.save(os.path.join(ASSETS_DIR, "prop_glasses_aviator.png"))

# 13. Heart Sunglasses
def gen_heart_shades():
    img = Image.new("RGBA", (512, 220), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Bridge
    d.line([(220, 90), (292, 90)], fill=(255, 255, 255, 255), width=5)
    # Left heart
    left_heart = [(140, 185), (55, 100), (60, 55), (105, 50), (140, 85), (175, 50), (220, 55), (225, 100)]
    d.polygon(left_heart, fill=(235, 30, 75, 200), outline=(255, 255, 255, 255))
    # Right heart
    right_heart = [(372, 185), (287, 100), (292, 55), (337, 50), (372, 85), (407, 50), (452, 55), (457, 100)]
    d.polygon(right_heart, fill=(235, 30, 75, 200), outline=(255, 255, 255, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_glasses_heart.png"))

# 14. Thug Life 8-Bit Pixel Shades
def gen_thug_life():
    img = Image.new("RGBA", (512, 160), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 8-bit stepped pixels
    px = 16
    # Left lens
    for y in range(4):
        for x in range(12):
            if y == 0 and x < 3: continue
            d.rectangle([(60 + x*px), (40 + y*px), (60 + (x+1)*px), (40 + (y+1)*px)], fill=(15, 15, 15, 255))
    # Specular white pixel blocks
    d.rectangle([76, 56, 76+px, 56+px], fill=(255, 255, 255, 255))
    d.rectangle([92, 72, 92+px, 72+px], fill=(255, 255, 255, 255))
    # Bridge
    d.rectangle([252, 56, 268, 72], fill=(15, 15, 15, 255))
    # Right lens
    for y in range(4):
        for x in range(12):
            if y == 0 and x < 3: continue
            d.rectangle([(268 + x*px), (40 + y*px), (268 + (x+1)*px), (40 + (y+1)*px)], fill=(15, 15, 15, 255))
    d.rectangle([284, 56, 284+px, 56+px], fill=(255, 255, 255, 255))
    d.rectangle([300, 72, 300+px, 72+px], fill=(255, 255, 255, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_glasses_thug.png"))

# 15. Royal Gold Crown
def gen_crown():
    img = Image.new("RGBA", (512, 280), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Crown base band
    d.rounded_rectangle([70, 200, 442, 245], radius=10, fill=(235, 185, 30, 255), outline=(190, 140, 20, 255), width=4)
    # 5 Crown peaks
    peaks = [
        (70, 200), (90, 80), (160, 160), (256, 40), (352, 160), (422, 80), (442, 200)
    ]
    d.polygon(peaks, fill=(250, 205, 45, 255), outline=(200, 150, 25, 255))
    # Jewels on peaks
    d.ellipse([80, 70, 100, 90], fill=(220, 30, 60, 255)) # Ruby
    d.ellipse([244, 30, 268, 54], fill=(30, 120, 240, 255)) # Sapphire
    d.ellipse([412, 70, 432, 90], fill=(40, 200, 80, 255)) # Emerald
    # Gems on band
    for gx in [130, 200, 256, 312, 382]:
        d.ellipse([gx-8, 215, gx+8, 231], fill=(255, 255, 255, 230))
    img.save(os.path.join(ASSETS_DIR, "prop_headwear_crown.png"))

# 16. Angel Halo
def gen_angel_halo():
    img = Image.new("RGBA", (512, 180), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Glowing golden ring
    d.ellipse([100, 40, 412, 130], outline=(255, 240, 130, 255), width=16)
    d.ellipse([108, 48, 404, 122], outline=(255, 215, 0, 255), width=8)
    img.save(os.path.join(ASSETS_DIR, "prop_magic_halo.png"))

# 17. Fire Demon Horns
def gen_demon_horns():
    img = Image.new("RGBA", (512, 280), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Left Horn (curved upward)
    left_horn = [(140, 240), (80, 180), (40, 100), (50, 30), (90, 70), (120, 140), (170, 210)]
    d.polygon(left_horn, fill=(40, 15, 20, 255))
    d.polygon(left_horn[:4] + [(65, 80)], fill=(230, 50, 40, 255))
    # Right Horn
    right_horn = [(372, 240), (432, 180), (472, 100), (462, 30), (422, 70), (392, 140), (342, 210)]
    d.polygon(right_horn, fill=(40, 15, 20, 255))
    d.polygon(right_horn[:4] + [(447, 80)], fill=(230, 50, 40, 255))
    img.save(os.path.join(ASSETS_DIR, "prop_magic_horns.png"))

# 18. Aesthetic Butterflies
def gen_butterflies():
    img = Image.new("RGBA", (512, 300), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Floating delicate pastel butterflies
    for cx, cy, col in [(120, 120, (255, 160, 200, 220)), (392, 110, (180, 160, 255, 220)), (256, 60, (255, 210, 140, 220))]:
        # Top wings
        d.ellipse([cx - 35, cy - 30, cx - 4, cy + 5], fill=col)
        d.ellipse([cx + 4, cy - 30, cx + 35, cy + 5], fill=col)
        # Bottom wings
        d.ellipse([cx - 25, cy + 2, cx - 2, cy + 26], fill=col)
        d.ellipse([cx + 2, cy + 2, cx + 25, cy + 26], fill=col)
        # Body
        d.line([(cx, cy - 25), (cx, cy + 22)], fill=(40, 30, 40, 255), width=3)
    img.save(os.path.join(ASSETS_DIR, "prop_beauty_butterflies.png"))

# Run generators
gen_puppy_ears()
gen_puppy_nose()
gen_bunny()
gen_kitty()
gen_bear()
gen_panda()
gen_fox()
gen_tiger()
gen_lion()
gen_unicorn()
gen_idontcare_shades()
gen_cyberpunk_visor()
gen_aviators()
gen_heart_shades()
gen_thug_life()
gen_crown()
gen_angel_halo()
gen_demon_horns()
gen_butterflies()

print("Props generation complete.")
