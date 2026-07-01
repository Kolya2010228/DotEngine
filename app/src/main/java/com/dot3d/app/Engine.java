package com.dot3d.app;

/**
 * Common surface API MainActivity drives, regardless of the render backend.
 * Implemented by the software {@link EngineView} and the experimental
 * {@link GLEngineView} (GPU). Lets MainActivity swap backends behind
 * {@code Settings.gpu()} without knowing which one is live.
 */
public interface Engine {
    void setMenuListener(EngineView.MenuListener l);
    void setPaused(boolean p);
    void setLoadedModel(Mesh m);
}
