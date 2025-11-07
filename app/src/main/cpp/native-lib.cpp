#include <jni.h>
#include <string>
#include <cmath> // For std::abs, log2
#include <algorithm> // For std::min, std::max, std::floor
#include <vector>

// Define a struct to mirror the Kotlin AffineTransform data class
struct AffineTransform {
    jdouble zx_x;
    jdouble zx_y;
    jdouble zx_c;
    jdouble zy_x;
    jdouble zy_y;
    jdouble zy_c;
};

// C++ equivalents of Kotlin Colors object
namespace Colors {
    inline int r(int color) { return (color >> 16) & 0xFF; }
    inline int g(int color) { return (color >> 8) & 0xFF; }
    inline int b(int color) { return color & 0xFF; }
    inline int rgb(int r, int g, int b) { return (0xFF << 24) | (r << 16) | (g << 8) | b; }
}

static std::vector<double> logMagnitudeLookupTable(65536);
static std::vector<double> logLogLookupTable(65536);

extern "C" JNIEXPORT void JNICALL
Java_org_jtb_fractaldreams_MandelbrotDreamService_00024Companion_initNative(
    JNIEnv* env,
    jobject /* this */) {
    for (int i = 0; i < 65536; i++) {
        double input = 4.0 + (static_cast<double>(i) / 65535.0) * 32.0;
        logMagnitudeLookupTable[i] = std::log2(input);
    }
    for (int i = 0; i < 65536; i++) {
        double input = 1.0 + (static_cast<double>(i) / 65535.0) * 1.585;
        logLogLookupTable[i] = std::log2(input);
    }
}

// Standalone helper function for calculating a single pixel's color
inline int calculate_pixel_color(
    int x, int y, const AffineTransform& transform, int maxIterations, double escapeRadiusSquared,
    const jint* colorPalette, int colorOffset, bool smoothColors, bool useLog2Lookup,
    double logMagnitudeScaleFactor, double logLogScaleFactor, double log2_2) {

    double c_re = transform.zx_x * x + transform.zx_y * y + transform.zx_c;
    double c_im = transform.zy_x * x + transform.zy_y * y + transform.zy_c;

    double z_re = 0.0;
    double z_im = 0.0;

    int i = 0;
    while (i < maxIterations) {
        double z_re_sq = z_re * z_re;
        double z_im_sq = z_im * z_im;

        if (z_re_sq + z_im_sq > escapeRadiusSquared) {
            break;
        }

        double next_z_im = 2.0 * z_re * z_im + c_im;
        z_re = z_re_sq - z_im_sq + c_re;
        z_im = next_z_im;
        i++;
    }

    if (i == maxIterations) {
        return colorPalette[maxIterations];
    }

    if (smoothColors) {
        double magSq = z_re * z_re + z_im * z_im;
        double logZn;
        double nu;

        if (useLog2Lookup) {
            int index = static_cast<int>((magSq - 4.0) * logMagnitudeScaleFactor);
            index = std::max(0, std::min(index, 65535));
            logZn = logMagnitudeLookupTable[index] / 2.0;
            int nuIndex = static_cast<int>((logZn - 1.0) * logLogScaleFactor);
            nuIndex = std::max(0, std::min(nuIndex, 65535));
            nu = logLogLookupTable[nuIndex] / log2_2;
        } else {
            logZn = std::log2(magSq) / 2.0;
            nu = std::log2(logZn) / log2_2;
        }

        double continuousIndex = std::max(0.0, i + 1 - nu);
        int index1 = static_cast<int>(continuousIndex);
        int index2 = index1 + 1;

        int localMaxIterations = maxIterations;
        int color1 = colorPalette[((index1 + colorOffset) % localMaxIterations + localMaxIterations) % localMaxIterations];
        int color2 = colorPalette[((index2 + colorOffset) % localMaxIterations + localMaxIterations) % localMaxIterations];

        float fraction = static_cast<float>(continuousIndex - std::floor(continuousIndex));
        int r = static_cast<int>(Colors::r(color1) * (1 - fraction) + Colors::r(color2) * fraction);
        int g = static_cast<int>(Colors::g(color1) * (1 - fraction) + Colors::g(color2) * fraction);
        int b = static_cast<int>(Colors::b(color1) * (1 - fraction) + Colors::b(color2) * fraction);

        return Colors::rgb(r, g, b);
    } else {
        return colorPalette[((i + colorOffset) % maxIterations + maxIterations) % maxIterations];
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_jtb_fractaldreams_MandelbrotDreamService_00024Companion_mandelbrotRenderBlock(
    JNIEnv* env,
    jobject /* this */,
    jint currentBlockHeight,
    jint currentBlockWidth,
    jint blockX,
    jint blockY,
    jdouble zx_x,
    jdouble zx_y,
    jdouble zx_c,
    jdouble zy_x,
    jdouble zy_y,
    jdouble zy_c,
    jint maxIterations,
    jdouble escapeRadiusSquared,
    jintArray colorPalette_,
    jint colorOffset,
    jboolean smoothColors,
    jboolean useLog2Lookup,
    jdouble logMagnitudeScaleFactor,
    jdouble logLogScaleFactor,
    jdouble log2_2,
    jbooleanArray isPixelSet_,
    jintArray colorArray_) {

    // Get native arrays from JNI
    jboolean isCopy;
    jint* colorPalette = (jint*)env->GetPrimitiveArrayCritical(colorPalette_, &isCopy);
    jboolean* isPixelSet = isPixelSet_ ? (jboolean*)env->GetPrimitiveArrayCritical(isPixelSet_, &isCopy) : nullptr;
    jint* colorArray = (jint*)env->GetPrimitiveArrayCritical(colorArray_, &isCopy);

    // Reconstruct AffineTransform
    AffineTransform transform = {zx_x, zx_y, zx_c, zy_x, zy_y, zy_c};

    for (int y = 0; y < currentBlockHeight; y++) {
        for (int x = 0; x < currentBlockWidth; x++) {
            int index = y * currentBlockWidth + x;
            if (isPixelSet == nullptr || !isPixelSet[index]) {
                colorArray[index] = calculate_pixel_color(
                    blockX + x, blockY + y, transform, maxIterations, escapeRadiusSquared,
                    colorPalette, colorOffset, smoothColors, useLog2Lookup,
                    logMagnitudeScaleFactor, logLogScaleFactor, log2_2);
            }
        }
    }

    // Release native arrays
    env->ReleasePrimitiveArrayCritical(colorPalette_, colorPalette, JNI_ABORT);
    if (isPixelSet) {
        env->ReleasePrimitiveArrayCritical(isPixelSet_, isPixelSet, JNI_ABORT);
    }
    env->ReleasePrimitiveArrayCritical(colorArray_, colorArray, 0);
}