package cn.mythicland.worldmanager;

/**
 * Lifecycle states exposed for managed worlds.
 */
public enum WorldStatus {
    DISCOVERED,
    PREPARING,
    QUEUED,
    LOADING,
    LOADED,
    UNLOADING,
    SAVING,
    FAILED
}
