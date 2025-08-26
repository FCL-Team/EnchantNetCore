package org.fcl.enchantnetcore.state;

import androidx.annotation.DrawableRes;

/** Texts for GuestVpnService notifications (connecting/connected/fail variants). */
public class GuestNoticeRes {

    @DrawableRes
    public final int icon;
    public final String titleConnecting, textConnecting;
    public final String titleConnected,  textConnected;
    public final String titleFailBoot,   textFailBoot;
    public final String titleFailCrash,  textFailCrash;
    public final String titleFailConn,   textFailConn;
    public final String btnExit;
    public final String motd; // passed to FakeLanBroadcaster

    public GuestNoticeRes(@DrawableRes int icon,
                          String titleConnecting, String textConnecting,
                          String titleConnected, String textConnected,
                          String titleFailBoot, String textFailBoot,
                          String titleFailCrash, String textFailCrash,
                          String titleFailConn, String textFailConn,
                          String btnExit,
                          String motd) {
        this.icon = icon;
        this.titleConnecting = titleConnecting; this.textConnecting = textConnecting;
        this.titleConnected  = titleConnected;  this.textConnected  = textConnected;
        this.titleFailBoot   = titleFailBoot;   this.textFailBoot   = textFailBoot;
        this.titleFailCrash  = titleFailCrash;  this.textFailCrash  = textFailCrash;
        this.titleFailConn   = titleFailConn;   this.textFailConn   = textFailConn;
        this.btnExit = btnExit;
        this.motd = motd;
    }
}