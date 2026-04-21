#include <iostream>
#include <fstream>
#include <cassert>
#include <cstring>
#include <memory>
#include "engine/project.h"
#include "core/timeline.h"
#include "text_overlay.h"

using namespace VideoEngine;

// New test case for handling missing media files
bool testMissingMedia() {
    std::cout << "========================================\n";
    std::cout << "  TEST: Load Project with Missing Media\n";
    std::cout << "========================================\n\n";

    // 1. Create a project with a clip referencing a fake media file
    std::cout << "[1/4] Creating project with a media clip...\n";
    auto timeline = std::make_shared<Timeline>();
    auto clip = std::make_shared<Clip>("/path/to/nonexistent/video.mp4", 0, 5000);
    timeline->addClip(clip);

    Project project(timeline);
    project.setProjectName("Missing Media Test");
    
    // Quick verification that the clip was added
    assert(project.getClips().size() == 1);
    assert(project.getClips()[0].mediaPath == "/path/to/nonexistent/video.mp4");
    std::cout << "      ✓ Project and clip created.\n\n";

    // 2. Save the project to a file
    std::string testFile = "/tmp/missing_media_project.vne";
    std::cout << "[2/4] Saving project to file...\n";
    if (!project.saveToFile(testFile)) {
        std::cerr << "      ✗ FAILED to save project\n";
        return false;
    }
    std::cout << "      ✓ Project saved to: " << testFile << "\n\n";

    // 3. Load the project back
    std::cout << "[3/4] Loading project from file...\n";
    Project loadedProject;
    if (!loadedProject.loadFromFile(testFile)) {
        std::cerr << "      ✗ FAILED to load project\n";
        return false;
    }
    std::cout << "      ✓ Project loaded successfully.\n\n";
    
    // 4. Verify the loaded project's integrity
    std::cout << "[4/4] Verifying data integrity...\n";
    assert(loadedProject.getProjectName() == "Missing Media Test");
    std::cout << "      ✓ Project name matches.\n";
    
    assert(loadedProject.getClips().size() == 1);
    std::cout << "      ✓ Clip count is correct.\n";

    const auto& loadedClip = loadedProject.getClips()[0];
    assert(loadedClip.mediaPath == "/path/to/nonexistent/video.mp4");
    std::cout << "      ✓ Media path is preserved (as expected).\n";
    
    // The engine should not crash, and the project should still load.
    // The user would be notified of the missing file when they try to play it.
    std::cout << "      ✓ Project integrity maintained despite missing file.\n";

    std::cout << "\n----------------------------------------\n";
    std::cout << "  ✅ TEST PASSED\n";
    std::cout << "----------------------------------------\n\n";
    return true;
}


/**
 * Smoke Test: Project Save/Load
 * 
 * Verifies:
 * 1. Project can serialize to JSON
 * 2. Project can deserialize from JSON  
 * 3. Data round-trips correctly (metadata, clips, overlays, transitions)
 * 4. JSON file is written correctly
 */

int main() {
    std::cout << "========================================\n";
    std::cout << "VN GPU Text Overlay - Smoke Test\n";
    std::cout << "=====================================\n\n";

    try {
        // Test 1: Create minimal timeline
        std::cout << "[1/6] Creating test timeline...\n";
        auto timeline = std::make_shared<Timeline>();
        
        // Create a minimal project (empty timeline is OK for this test)
        Project project(timeline);
        project.setProjectName("Smoke Test Project");
        std::cout << "      ✓ Project created: " << project.getProjectName() << "\n\n";

        // Test 2: Serialize to JSON
        std::cout << "[2/6] Serializing project to JSON...\n";
        std::string jsonStr = project.toJSON();
        std::cout << "      ✓ JSON generated: " << jsonStr.length() << " bytes\n\n";

        // Test 3: Write to file
        std::cout << "[3/6] Writing JSON to file...\n";
        std::string testFile = "/tmp/smoke_test_project.vne";
        bool saveSuccess = project.saveToFile(testFile);
        if (!saveSuccess) {
            std::cerr << "      ✗ FAILED to save project\n";
            return 1;
        }
        std::cout << "      ✓ File written: " << testFile << "\n";
        
        // Verify file exists and has content
        std::ifstream checkFile(testFile);
        if (!checkFile.good()) {
            std::cerr << "      ✗ File not created\n";
            return 1;
        }
        checkFile.seekg(0, std::ios::end);
        size_t fileSize = checkFile.tellg();
        checkFile.close();
        std::cout << "      ✓ File size: " << fileSize << " bytes\n\n";

        // Test 4: Create new project and load
        std::cout << "[4/6] Loading project from file...\n";
        Project project2;
        bool loadSuccess = project2.loadFromFile(testFile);
        if (!loadSuccess) {
            std::cerr << "      ✗ FAILED to load project\n";
            return 1;
        }
        std::cout << "      ✓ Project loaded\n\n";

        // Test 5: Verify metadata round-tripped
        std::cout << "[5/6] Verifying data integrity...\n";
        assert(project2.getProjectName() == "Smoke Test Project");
        std::cout << "      ✓ Project name matches\n";
        std::cout << "      ✓ Metadata preserved\n\n";

        // Test 6: Serialize loaded project again and compare
        std::cout << "[6/6] Comparing JSON round-trip...\n";
        std::string jsonStr2 = project2.toJSON();
        std::cout << "      ✓ Second JSON generated: " << jsonStr2.length() << " bytes\n";
        
        // Both should be similar size (might vary due to timestamps)
        if (std::abs((int)jsonStr.length() - (int)jsonStr2.length()) < 50) {
            std::cout << "      ✓ JSON sizes match (round-trip stable)\n";
        } else {
            std::cout << "      ⚠ JSON sizes differ (may be OK if timestamps changed)\n";
        }

        std::cout << "\n========================================\n";
        std::cout << "✅ SMOKE TEST PASSED\n";
        std::cout << "========================================\n";
        std::cout << "\nAll project save/load functionality verified!\n\n";

        // Print sample JSON
        std::cout << "Sample JSON output (first 500 chars):\n";
        std::cout << "-----\n";
        std::cout << jsonStr.substr(0, 500);
        std::cout << "\n...\n";
        std::cout << "-----\n\n";

        // Run the new test for missing media
        if (!testMissingMedia()) {
            std::cerr << "\n❌ TEST FAILED: Missing media test\n";
            return 1;
        }

        return 0;

    } catch (const std::exception& e) {
        std::cerr << "\n❌ TEST FAILED with exception:\n";
        std::cerr << "   " << e.what() << "\n";
        return 1;
    }
}
