package org.fcl.enchantnetcore.state;

import androidx.annotation.DrawableRes;

/** Texts for LanScanService notification (scanning phase). */
public class LanScanNoticeRes {

    @DrawableRes
    public final int icon;
    public final String titleScanning, textScanning;

    public LanScanNoticeRes(@DrawableRes int icon, String titleScanning, String textScanning) {
        this.icon = icon;
        this.titleScanning = titleScanning;
        this.textScanning = textScanning;
    }
}