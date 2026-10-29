package io.deepseekharness.mobile.shizuku;

import io.deepseekharness.mobile.shizuku.IDeviceShellCallback;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;
import io.deepseekharness.mobile.shizuku.VirtualScreenFrameHandle;

interface IDeviceShellService {
    String createSession(int columns, int rows, in IDeviceShellCallback callback) = 0;
    void write(String sessionId, in byte[] data) = 1;
    void resize(String sessionId, int columns, int rows) = 2;
    void closeSession(String sessionId) = 3;
    void closeAll() = 4;
    String startVirtualScreen(String component, int width, int height, int dpi, IBinder owner) = 5;
    String virtualScreenState() = 6;
    String virtualScreenAction(String parameters) = 7;
    ParcelFileDescriptor virtualScreenSnapshot(String sessionId) = 8;
    VirtualScreenFrameHandle virtualScreenFrame(String sessionId) = 9;
    ParcelFileDescriptor virtualScreenSnapshotEnvelope(String sessionId) = 10;
    // Companion game control: whole multi-finger gestures, their cancel, and a raw main-display frame.
    String injectGesture(String plan) = 11;
    void cancelGesture() = 12;
    ParcelFileDescriptor captureScreen() = 13;
    // Shizuku reserves this transaction for removing a UserService.
    void destroy() = 16777114;
}
