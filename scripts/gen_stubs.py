#!/usr/bin/env python3
"""Generate minimal Java stubs to allow compiling CarPlay hook classes without MU1316-final.jar."""
import os
from pathlib import Path

STUBS = {
    'de/audi/app/terminalmode/IContext.java': 'package de.audi.app.terminalmode;\nimport de.audi.app.terminalmode.osgi.IServiceManager;\npublic interface IContext {\n    IServiceManager getServiceManager();\n}\n',
    'de/audi/atip/base/IFrameworkAccess.java': 'package de.audi.atip.base;\nimport de.audi.atip.hmi.HMIService;\npublic interface IFrameworkAccess {\n    long getUTCTime();\n    long convertUTCTimeToLocalTime(long utcMs);\n    int getKombiType();\n    HMIService getHMIService();\n}\n',
    'de/audi/atip/hmi/HMIService.java': 'package de.audi.atip.hmi;\nimport de.audi.atip.hmi.view.IDisplayManager;\npublic interface HMIService {\n    IDisplayManager getDisplayManager();\n}\n',
    'de/audi/atip/hmi/intercommunication/NaviMoKoKDKConstants.java': 'package de.audi.atip.hmi.intercommunication;\npublic interface NaviMoKoKDKConstants {}\n',
    'de/audi/atip/hmi/view/IDisplayManager.java': 'package de.audi.atip.hmi.view;\npublic interface IDisplayManager {\n    int getCurrentContextID(int terminal);\n    void switchContext(int ctx, int terminal, Object opt);\n    void setUpdateRate(int terminal, int rate);\n}\n',
    'de/audi/atip/interapp/combi/bap/navi/CombiBAPServiceNavi.java': 'package de.audi.atip.interapp.combi.bap.navi;\nimport de.audi.atip.interapp.combi.bap.navi.data.*;\npublic interface CombiBAPServiceNavi {\n    void updateActiveRGType(int t);\n    void updateCurrentPositionInfo(String s);\n    void updateTurnToInfo(String a, String b);\n    void updateDestinationInfo(CombiBAPDestinationInfo d);\n    void updateDistanceToDestination(int val, int unit, boolean stop);\n    void updateDistanceToNextManeuver(int val, int unit, boolean on, int bar);\n    void updateExitView(int v, int num);\n    void updateLaneGuidance(boolean on, CombiBAPNaviLaneGuidanceData[] data);\n    void updateManeuverDescriptor(CombiBAPNaviManeuverDescriptor[] desc);\n    void updateManeuverState(int s);\n    void updateRGStatus(int s);\n    void updateTimeToDestination(int mode, int fmt, long val);\n}\n',
    'de/audi/atip/interapp/combi/bap/navi/data/CombiBAPDestinationInfo.java': 'package de.audi.atip.interapp.combi.bap.navi.data;\npublic class CombiBAPDestinationInfo {\n    public CombiBAPDestinationInfo(CombiBAPNaviDestination d) {}\n}\n',
    'de/audi/atip/interapp/combi/bap/navi/data/CombiBAPNaviDestination.java': 'package de.audi.atip.interapp.combi.bap.navi.data;\npublic class CombiBAPNaviDestination {\n    public CombiBAPNaviDestination(String a, String b, String c, String d, String e, String f, String g) {}\n}\n',
    'de/audi/atip/interapp/combi/bap/navi/data/CombiBAPNaviLaneGuidanceData.java': 'package de.audi.atip.interapp.combi.bap.navi.data;\npublic class CombiBAPNaviLaneGuidanceData {\n    public CombiBAPNaviLaneGuidanceData(int pos, int dir, int status, int angle) {}\n    public CombiBAPNaviLaneGuidanceData(short pos, short dir, byte[] desc, short status, byte a, byte b, byte c, byte d) {}\n}\n',
    'de/audi/atip/interapp/combi/bap/navi/data/CombiBAPNaviManeuverDescriptor.java': 'package de.audi.atip.interapp.combi.bap.navi.data;\npublic class CombiBAPNaviManeuverDescriptor {\n    public CombiBAPNaviManeuverDescriptor(int main, int dir, int z, byte[] side) {}\n}\n',
    'de/audi/atip/log/LogChannel.java': 'package de.audi.atip.log;\npublic class LogChannel {\n    public void log(int level, String msg) {}\n}\n',
    'de/audi/atip/metrics/DateMetric.java': 'package de.audi.atip.metrics;\npublic class DateMetric {\n    public static int timeFormat = 0;\n}\n',
    'de/audi/atip/metrics/Distance.java': 'package de.audi.atip.metrics;\npublic class Distance {\n    public static final int KM = 1, METERS = 0, NONE = 2, MILES = 3;\n    private static int unit = KM;\n    public static int getSystemUnit() { return unit; }\n    public static void setSystemUnit(int u) { unit = u; }\n}\n',
    'de/audi/atip/power/PowerEventListener.java': 'package de.audi.atip.power;\npublic interface PowerEventListener {}\n',
    'de/audi/tghu/fwhmi/IDisplayManagerKombiControl.java': 'package de.audi.tghu.fwhmi;\npublic interface IDisplayManagerKombiControl {\n    void setOpacity(int a, int b, int c);\n    void setPosition(int a, int b, int c, int d);\n    void setCropping(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j);\n}\n',
    'de/audi/tghu/navi/app/Navigation.java': 'package de.audi.tghu.navi.app;\nimport de.audi.tghu.navi.app.cluster.ClusterService;\npublic class Navigation {\n    public static Navigation getInstance() { return new Navigation(); }\n    public ClusterService getClusterService() { return null; }\n    public de.audi.tghu.navi.app.routeguidance.IRouteManager getRouteManager() { return null; }\n}\n',
    'de/audi/tghu/navi/app/cluster/BAPDistanceFormatter.java': 'package de.audi.tghu.navi.app.cluster;\nimport de.audi.atip.log.LogChannel;\npublic class BAPDistanceFormatter {\n    public static class BAPDistance {\n        public int getValue() { return 0; }\n        public int getUnit() { return 0; }\n    }\n    public BAPDistanceFormatter(LogChannel ch) {}\n    public BAPDistance formatDistanceToTurn(int m, boolean metric) { return new BAPDistance(); }\n    public BAPDistance formatDistanceToDestination(int m, boolean metric) { return new BAPDistance(); }\n}\n',
    'de/audi/tghu/navi/app/cluster/ClusterService.java': 'package de.audi.tghu.navi.app.cluster;\nimport de.audi.tghu.navi.app.command.DSIResponseContainer;\npublic class ClusterService implements de.audi.atip.hmi.intercommunication.NaviMoKoKDKConstants, de.audi.atip.power.PowerEventListener {\n    public KOMOService getKomoService() { return null; }\n    public ClusterViewMode getClusterViewMode() { return null; }\n    public DSIResponseContainer getDSIResponseContainer() { return null; }\n    public void setRouteGuidanceAborted() {}\n    public void updateRGIString(short[] s) {}\n    public void triggerRefreshRGIValid() {}\n    public void setKOMODataRate(int r) {}\n}\n',
    'de/audi/tghu/navi/app/cluster/ClusterViewMode.java': 'package de.audi.tghu.navi.app.cluster;\npublic class ClusterViewMode {\n    public void setDataRate(int r) {}\n    public void setGFXAvailable(boolean b) {}\n}\n',
    'de/audi/tghu/navi/app/cluster/KOMOService.java': 'package de.audi.tghu.navi.app.cluster;\npublic interface KOMOService {\n    void setClusterViewMode(ClusterViewMode m);\n    void updateDataRate(int r, int x);\n    void updateGfxState(int s, int x);\n}\n',
    'de/audi/tghu/navi/app/command/DSIResponseContainer.java': 'package de.audi.tghu.navi.app.command;\npublic class DSIResponseContainer {\n    public Object getDSICarplayListener() { return null; }\n    public boolean isRgActive() { return false; }\n    public void setRgActive(boolean b) {}\n}\n',
    'de/audi/tghu/navi/app/routeguidance/IRouteManager.java': 'package de.audi.tghu.navi.app.routeguidance;\npublic interface IRouteManager {\n    org.dsi.ifc.navigation.Route getRoute();\n    void stopRouteGuidance();\n}\n',
    'de/audi/tghu/navi/app/util/Util.java': 'package de.audi.tghu.navi.app.util;\nimport de.audi.atip.base.IFrameworkAccess;\npublic class Util {\n    public static boolean isClusterMapMOST(IFrameworkAccess fw) { return true; }\n}\n',
    'de/esolutions/hmi/widgets/audi/base/Layout.java': 'package de.esolutions.hmi.widgets.audi.base;\npublic class Layout {\n    public static class Rect {\n        public int x, y, width, height;\n    }\n    public Rect getLayer(int l) { return null; }\n    public int getIntegerConstant(int id) { return 0; }\n}\n',
    'org/dsi/ifc/base/DSIListener.java': 'package org.dsi.ifc.base;\npublic interface DSIListener {}\n',
    'org/dsi/ifc/keypanel/DSIKeyPanel.java': 'package org.dsi.ifc.keypanel;\npublic interface DSIKeyPanel {}\n',
    'org/dsi/ifc/keypanel/DSIKeyPanelListener.java': 'package org.dsi.ifc.keypanel;\nimport org.dsi.ifc.base.DSIListener;\npublic interface DSIKeyPanelListener extends DSIListener {}\n',
    'org/dsi/ifc/navigation/Route.java': 'package org.dsi.ifc.navigation;\npublic class Route {}\n',

    # ---- org.dsi.ifc.androidauto2 (AndroidAutoNavTap static-check only; verified via
    #      javap on the stock MU1316-final.jar -- see java_patch/com/luka/carplay/aa/
    #      AndroidAutoNavTap.java for the full method list / source of truth). Never
    #      shipped: these stubs only let javac type-check the tap off the host JDK. ----
    'org/dsi/ifc/androidauto2/DSIAndroidAuto2Listener.java': 'package org.dsi.ifc.androidauto2;\nimport org.dsi.ifc.base.DSIListener;\npublic interface DSIAndroidAuto2Listener extends DSIListener {\n    void videoFocusRequestNotification(int a, int b);\n    void videoAvailable(boolean a, int b);\n    void audioFocusRequestNotification(int a, int b);\n    void audioAvailable(int a, boolean b, int c);\n    void voiceSessionNotification(int a, int b);\n    void microphoneRequestNotification(int a, int b);\n    void navFocusRequestNotification(int a, int b);\n    void updateCallState(CallState[] a, int b);\n    void updateTelephonyState(TelephonyState a, int b);\n    void updateNowPlayingData(TrackData a, int b);\n    void updatePlaybackState(PlaybackInfo a, int b);\n    void updatePlayposition(int a, int b);\n    void updateCoverArtUrl(org.dsi.ifc.global.ResourceLocator a, int b);\n    void updateNavigationNextTurnEvent(String a, int b, int c, int d, int e, int f);\n    void updateNavigationNextTurnDistance(int a, int b, int c);\n    void setExternalDestination(double a, double b, String c, String d, int e);\n    void bluetoothPairingRequest(String a, int b);\n}\n',
    'org/dsi/ifc/androidauto2/CallState.java': 'package org.dsi.ifc.androidauto2;\npublic class CallState {}\n',
    'org/dsi/ifc/androidauto2/TelephonyState.java': 'package org.dsi.ifc.androidauto2;\npublic class TelephonyState {}\n',
    'org/dsi/ifc/androidauto2/TrackData.java': 'package org.dsi.ifc.androidauto2;\npublic class TrackData {}\n',
    'org/dsi/ifc/androidauto2/PlaybackInfo.java': 'package org.dsi.ifc.androidauto2;\npublic class PlaybackInfo {}\n',
    'org/dsi/ifc/androidauto2/Constants.java': 'package org.dsi.ifc.androidauto2;\npublic interface Constants {\n    int NAVIGATIONTURNSIDE_UNSPECIFIED = 0, NAVIGATIONTURNSIDE_LEFT = 1, NAVIGATIONTURNSIDE_RIGHT = 2;\n    int NAVIGATIONTURNEVENT_UNKNOWN = 0, NAVIGATIONTURNEVENT_DEPART = 1, NAVIGATIONTURNEVENT_NAME_CHANGE = 2,\n        NAVIGATIONTURNEVENT_SLIGHT_TURN = 3, NAVIGATIONTURNEVENT_TURN = 4, NAVIGATIONTURNEVENT_SHARP_TURN = 5,\n        NAVIGATIONTURNEVENT_U_TURN = 6, NAVIGATIONTURNEVENT_ON_RAMP = 7, NAVIGATIONTURNEVENT_OFF_RAMP = 8,\n        NAVIGATIONTURNEVENT_FORK = 9, NAVIGATIONTURNEVENT_MERGE = 10, NAVIGATIONTURNEVENT_ROUNDABOUT_ENTER = 11,\n        NAVIGATIONTURNEVENT_ROUNDABOUT_EXIT = 12, NAVIGATIONTURNEVENT_ROUNDABOUT_ENTER_AND_EXIT = 13,\n        NAVIGATIONTURNEVENT_STRAIGHT = 14, NAVIGATIONTURNEVENT_FERRY_BOAT = 16, NAVIGATIONTURNEVENT_FERRY_TRAIN = 17,\n        NAVIGATIONTURNEVENT_DESTINATION = 19;\n    int NAVFOCUS_NATIVE = 1, NAVFOCUS_PROJECTED = 2;\n}\n',
    'org/dsi/ifc/global/ResourceLocator.java': 'package org.dsi.ifc.global;\npublic class ResourceLocator {}\n',

    # ---- OSGi / TerminalMode service lookup (also used by SteeringWheelInputModule,
    #      which has the proven registerDSIListener precedent this tap mirrors). ----
    'org/osgi/framework/ServiceRegistration.java': 'package org.osgi.framework;\npublic interface ServiceRegistration {\n    void unregister();\n}\n',
    'de/audi/app/terminalmode/osgi/IServiceManager.java': 'package de.audi.app.terminalmode.osgi;\nimport org.osgi.framework.ServiceRegistration;\npublic interface IServiceManager {\n    ServiceRegistration registerDSIListener(int instance, String listenerInterfaceName, Object listener);\n}\n',
}

def main():
    base = Path("build_stubs")
    for rel_path, code in STUBS.items():
        target = base / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(code)
    print(f"Generated {len(STUBS)} stubs in {base}/")

if __name__ == "__main__":
    main()
