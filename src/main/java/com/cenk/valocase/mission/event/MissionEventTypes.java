package com.cenk.valocase.mission.event;

/**
 * Mission event type constants. Gameplay services reference these when
 * publishing progress events; mission definitions match on the same strings.
 */
public final class MissionEventTypes {

    public static final String CASE_OPENED = "CASE_OPENED";
    public static final String SKIN_SOLD = "SKIN_SOLD";
    public static final String BATTLE_WON = "BATTLE_WON";
    public static final String UPGRADE_SUCCEEDED = "UPGRADE_SUCCEEDED";

    private MissionEventTypes() {
    }
}
