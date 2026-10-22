#!/usr/bin/env python3
"""
Update StatusTemplate.kt so all 50 templates belong to exactly the 5 requested categories:
1. Trending (10)
2. Love (10)
3. Attitude (10)
4. Emotional (10)
5. Celebration (10)
"""

file_path = "/home/am/storyline/android/app/src/main/kotlin/com/video/engine/status/StatusTemplate.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# Replace Sad with Emotional
content = content.replace('category = "Sad"', 'category = "Emotional"')

# Map lyrical templates to Attitude and Emotional
content = content.replace(
    'id = "tpl_lyr_urdu_shayari",\n            title = "Urdu Romantic Shayari",\n            category = "Lyrical"',
    'id = "tpl_lyr_urdu_shayari",\n            title = "Urdu Romantic Shayari",\n            category = "Emotional"'
)
content = content.replace(
    'id = "tpl_lyr_hindi_kavita",\n            title = "Dard Bhari Kavita",\n            category = "Lyrical"',
    'id = "tpl_lyr_hindi_kavita",\n            title = "Dard Bhari Kavita",\n            category = "Emotional"'
)
content = content.replace(
    'id = "tpl_lyr_neon_glow_lyrics",\n            title = "Neon Glow Kinetic Lyrics",\n            category = "Lyrical"',
    'id = "tpl_lyr_neon_glow_lyrics",\n            title = "Neon Glow Kinetic Lyrics",\n            category = "Attitude"'
)
content = content.replace(
    'id = "tpl_lyr_paper_tear",\n            title = "Paper Tear Aesthetic",\n            category = "Lyrical"',
    'id = "tpl_lyr_paper_tear",\n            title = "Paper Tear Aesthetic",\n            category = "Attitude"'
)
content = content.replace(
    'id = "tpl_lyr_golden_calligraphy",\n            title = "Royal Gold Calligraphy",\n            category = "Lyrical"',
    'id = "tpl_lyr_golden_calligraphy",\n            title = "Royal Gold Calligraphy",\n            category = "Attitude"'
)
content = content.replace(
    'id = "tpl_lyr_vintage_typewriter",\n            title = "Typewriter Poetry Story",\n            category = "Lyrical"',
    'id = "tpl_lyr_vintage_typewriter",\n            title = "Typewriter Poetry Story",\n            category = "Attitude"'
)

# Map Birthday, Friendship, Devotional to Celebration
content = content.replace('category = "Birthday"', 'category = "Celebration"')
content = content.replace('category = "Friendship"', 'category = "Celebration"')
content = content.replace('category = "Devotional"', 'category = "Celebration"')

# Update categories list at the end
old_categories = 'val categories: List<String> = listOf("All", "Trending", "Love", "Sad", "Attitude", "Lyrical", "Birthday", "Friendship", "Devotional")'
new_categories = 'val categories: List<String> = listOf("All", "Trending", "Love", "Attitude", "Emotional", "Celebration")'
content = content.replace(old_categories, new_categories)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Updated StatusTemplate.kt categories.")
