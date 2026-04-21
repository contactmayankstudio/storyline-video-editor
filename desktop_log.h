#pragma once

#include <cstdio> // For printf

#ifndef LOG_TAG_DESKTOP
#define LOG_TAG_DESKTOP "DesktopLog"
#endif

// Using printf for consistency with C-style variadic arguments
#define LOGI(fmt, ...) printf("[INFO] %s: " fmt "\n", LOG_TAG_DESKTOP, ##__VA_ARGS__)
#define LOGE(fmt, ...) fprintf(stderr, "[ERROR] %s: " fmt "\n", LOG_TAG_DESKTOP, ##__VA_ARGS__)
#define LOGD(fmt, ...) printf("[DEBUG] %s: " fmt "\n", LOG_TAG_DESKTOP, ##__VA_ARGS__)
#define LOGW(fmt, ...) printf("[WARN] %s: " fmt "\n", LOG_TAG_DESKTOP, ##__VA_ARGS__)