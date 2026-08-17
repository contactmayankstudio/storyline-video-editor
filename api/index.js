const express = require('express');
const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(express.json());

// Trending Assets Mock Database
const trendingAssets = [
    {
        id: "asset_001",
        type: "LUT",
        name: "Cinematic Teal & Orange",
        description: "Premium cinematic look for travel vlogs.",
        downloadUrl: "https://assets.storyline.app/luts/cinematic-teal-orange.cube",
        thumbnailUrl: "https://assets.storyline.app/thumbnails/cinematic-teal-orange.jpg",
        priceTier: "premium",
        trendingScore: 98,
        createdAt: "2026-08-01T10:00:00Z"
    },
    {
        id: "asset_002",
        type: "Sticker",
        name: "Neon Glow Pack",
        description: "Animated neon stickers for dynamic videos.",
        downloadUrl: "https://assets.storyline.app/stickers/neon-glow-pack.zip",
        thumbnailUrl: "https://assets.storyline.app/thumbnails/neon-glow-pack.jpg",
        priceTier: "free",
        trendingScore: 85,
        createdAt: "2026-08-05T14:30:00Z"
    },
    {
        id: "asset_003",
        type: "Effect",
        name: "Vintage Film Grain",
        description: "Authentic 8mm film grain overlay.",
        downloadUrl: "https://assets.storyline.app/effects/vintage-grain.mp4",
        thumbnailUrl: "https://assets.storyline.app/thumbnails/vintage-grain.jpg",
        priceTier: "premium",
        trendingScore: 92,
        createdAt: "2026-07-28T09:15:00Z"
    }
];

// GET /api/v1/assets/trending
// Returns the list of trending assets for the mobile app
app.get('/api/v1/assets/trending', (req, res) => {
    // Sort by trending score descending
    const sortedAssets = [...trendingAssets].sort((a, b) => b.trendingScore - a.trendingScore);
    
    res.status(200).json({
        success: true,
        data: sortedAssets,
        meta: {
            total: sortedAssets.length,
            timestamp: new Date().toISOString()
        }
    });
});

// GET /api/v1/assets/:id
// Retrieve a specific asset by ID
app.get('/api/v1/assets/:id', (req, res) => {
    const asset = trendingAssets.find(a => a.id === req.params.id);
    if (asset) {
        res.status(200).json({
            success: true,
            data: asset
        });
    } else {
        res.status(404).json({
            success: false,
            message: "Asset not found"
        });
    }
});

// Health check endpoint
app.get('/health', (req, res) => {
    res.status(200).json({ status: 'ok', service: 'cloud-asset-store-api' });
});

app.listen(PORT, () => {
    console.log(`Cloud Asset Store API running on port ${PORT}`);
});
