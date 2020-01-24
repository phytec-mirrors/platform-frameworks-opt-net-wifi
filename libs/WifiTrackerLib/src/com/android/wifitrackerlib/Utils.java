/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wifitrackerlib;

import static com.android.wifitrackerlib.StandardWifiEntry.ssidAndSecurityToStandardWifiEntryKey;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_EAP;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_EAP_SUITE_B;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_NONE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_OWE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_PSK;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_SAE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_WEP;

import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.groupingBy;

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Utility methods for WifiTrackerLib.
 */
class Utils {
    // Returns the ScanResult with the best RSSI from a list of ScanResults.
    @Nullable
    static ScanResult getBestScanResultByLevel(@NonNull List<ScanResult> scanResults) {
        if (scanResults.isEmpty()) return null;

        return Collections.max(scanResults, comparingInt(scanResult -> scanResult.level));
    }

    // Returns a list of SECURITY types supported by a ScanResult.
    static List<Integer> getSecurityTypesFromScanResult(@NonNull ScanResult scan) {
        final List<Integer> securityTypes = new ArrayList<>();
        if (scan.capabilities == null) {
            securityTypes.add(SECURITY_NONE);
        } else if (scan.capabilities.contains("PSK") && scan.capabilities.contains("SAE")) {
            securityTypes.add(SECURITY_PSK);
            securityTypes.add(SECURITY_SAE);
        } else if (scan.capabilities.contains("OWE_TRANSITION")) {
            securityTypes.add(SECURITY_NONE);
            securityTypes.add(SECURITY_OWE);
        } else if (scan.capabilities.contains("OWE")) {
            securityTypes.add(SECURITY_OWE);
        } else if (scan.capabilities.contains("WEP")) {
            securityTypes.add(SECURITY_WEP);
        } else if (scan.capabilities.contains("SAE")) {
            securityTypes.add(SECURITY_SAE);
        } else if (scan.capabilities.contains("PSK")) {
            securityTypes.add(SECURITY_PSK);
        } else if (scan.capabilities.contains("EAP_SUITE_B_192")) {
            securityTypes.add(SECURITY_EAP_SUITE_B);
        } else if (scan.capabilities.contains("EAP")) {
            securityTypes.add(SECURITY_EAP);
        } else {
            securityTypes.add(SECURITY_NONE);
        }
        return securityTypes;
    }

    // Returns the SECURITY type supported by a WifiConfiguration
    @WifiEntry.Security
    static int getSecurityTypeFromWifiConfiguration(@NonNull WifiConfiguration config) {
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            return SECURITY_SAE;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return SECURITY_PSK;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
            return SECURITY_EAP_SUITE_B;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            return SECURITY_EAP;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
            return SECURITY_OWE;
        }
        return (config.wepKeys[0] != null) ? SECURITY_WEP : SECURITY_NONE;
    }

    /**
     * Maps ScanResults into any number of WifiEntry keys each ScanResult matches. If
     * chooseSingleSecurity is true, then ScanResults with multiple security capabilities will be
     * matched to a single security type for the purpose of user selection.
     *
     * @param scanResults ScanResults to be mapped.
     * @param chooseSingleSecurity If this is true, map scan results with multiple security
     *                             capabilities to a single security for coalescing into a single
     *                             WifiEntry.
     * @param wifiConfigsByKey Mapping of WifiConfiguration to WifiEntry key. Only used if
     *                         chooseSingleSecurity is true.
     * @param isWpa3SaeSupported If this is false, do not map to SECURITY_SAE
     * @param isWpa3SuiteBSupported If this is false, do not map to SECURITY_EAP_SUITE_B
     * @param isEnhancedOpenSupported If this is false, do not map to SECURITY_OWE
     * @return Map of WifiEntry key to list of corresponding ScanResults.
     */
    static Map<String, List<ScanResult>> mapScanResultsToKey(
            @NonNull List<ScanResult> scanResults,
            boolean chooseSingleSecurity,
            @Nullable Map<String, WifiConfiguration> wifiConfigsByKey,
            boolean isWpa3SaeSupported,
            boolean isWpa3SuiteBSupported,
            boolean isEnhancedOpenSupported) {
        if (wifiConfigsByKey == null) {
            wifiConfigsByKey = new HashMap<>();
        }
        final Map<String, List<ScanResult>> scanResultsBySsid = scanResults.stream()
                .filter(scanResult -> !TextUtils.isEmpty(scanResult.SSID))
                .collect(groupingBy(scanResult -> scanResult.SSID));
        final Map<String, List<ScanResult>> scanResultsByKey = new HashMap<>();

        for (String ssid : scanResultsBySsid.keySet()) {
            final boolean pskConfigExists = wifiConfigsByKey.containsKey(
                    ssidAndSecurityToStandardWifiEntryKey(ssid, SECURITY_PSK));
            final boolean saeConfigExists = wifiConfigsByKey.containsKey(
                    ssidAndSecurityToStandardWifiEntryKey(ssid, SECURITY_SAE));
            final boolean openConfigExists = wifiConfigsByKey.containsKey(
                    ssidAndSecurityToStandardWifiEntryKey(ssid, SECURITY_NONE));
            final boolean oweConfigExists = wifiConfigsByKey.containsKey(
                    ssidAndSecurityToStandardWifiEntryKey(ssid, SECURITY_OWE));

            boolean pskInRange = false;
            boolean saeInRange = false;
            boolean oweInRange = false;
            boolean openInRange = false;
            for (ScanResult scan : scanResultsBySsid.get(ssid)) {
                final List<Integer> securityTypes = getSecurityTypesFromScanResult(scan);
                if (securityTypes.contains(SECURITY_PSK)) {
                    pskInRange = true;
                }
                if (securityTypes.contains(SECURITY_SAE)) {
                    saeInRange = true;
                }
                if (securityTypes.contains(SECURITY_OWE)) {
                    oweInRange = true;
                }
                if (securityTypes.contains(SECURITY_NONE)) {
                    openInRange = true;
                }
            }

            for (ScanResult scan : scanResultsBySsid.get(ssid)) {
                List<Integer> securityTypes = getSecurityTypesFromScanResult(scan);
                List<Integer> chosenSecurityTypes = new ArrayList<>();
                // Ignore security types that are unsupported
                if (!isWpa3SaeSupported) {
                    securityTypes.remove((Integer) SECURITY_SAE);
                }
                if (!isWpa3SuiteBSupported) {
                    securityTypes.remove((Integer) SECURITY_EAP_SUITE_B);
                }
                if (!isEnhancedOpenSupported) {
                    securityTypes.remove((Integer) SECURITY_OWE);
                }

                final boolean isSae = securityTypes.contains(SECURITY_SAE)
                        && !securityTypes.contains(SECURITY_PSK);
                final boolean isPsk = securityTypes.contains(SECURITY_PSK)
                        && !securityTypes.contains(SECURITY_SAE);
                final boolean isPskSaeTransition = securityTypes.contains(SECURITY_PSK)
                        && securityTypes.contains(SECURITY_SAE);
                final boolean isOwe = securityTypes.contains(SECURITY_OWE)
                        && !securityTypes.contains(SECURITY_NONE);
                final boolean isOweTransition = securityTypes.contains(SECURITY_NONE)
                        && securityTypes.contains(SECURITY_OWE);
                final boolean isOpen = securityTypes.contains(SECURITY_NONE)
                        && !securityTypes.contains(SECURITY_OWE);

                if (chooseSingleSecurity) {
                    if (isPsk) {
                        if (!pskConfigExists && saeConfigExists && saeInRange) {
                            // If we don't have a PSK config, but there is an SAE AP in-range and
                            // an SAE config we can use for connection, then ignore the PSK AP so
                            // that the user only has the SAE AP to select.
                            continue;
                        } else {
                            chosenSecurityTypes.add(SECURITY_PSK);
                        }
                    } else if (isPskSaeTransition) {
                        // Map to SAE if we have an SAE config and no PSK config (use SAE config to
                        // connect). Else, map to PSK for wider compatibility.
                        if (!pskConfigExists && saeConfigExists) {
                            chosenSecurityTypes.add(SECURITY_SAE);
                        } else {
                            chosenSecurityTypes.add(SECURITY_PSK);
                        }
                    } else if (isSae) {
                        // Map to SAE if we either
                        // 1) have an SAE config and no PSK config (use SAE config to connect).
                        // 2) have no configs at all, and no PSK APs are in range. (save new
                        //    network with SAE security).
                        // Else, map to PSK for wider compatibility.
                        if (!pskConfigExists && (saeConfigExists || !pskInRange)) {
                            chosenSecurityTypes.add(SECURITY_SAE);
                        } else {
                            chosenSecurityTypes.add(SECURITY_PSK);
                        }
                    } else if (isOwe) {
                        // If an open AP is in range, use it instead if we have a config for it and
                        // no OWE config.
                        if (openInRange && openConfigExists && !oweConfigExists) {
                            continue;
                        }
                    } else if (isOweTransition) {
                        // Map to OWE if we either
                        // 1) have an OWE config (use OWE config to connect).
                        // 2) have no configs at all (save new network with OWE security).
                        // Otherwise, if we have an open config only, map to open security so that
                        // config is used for connection.
                        if (oweConfigExists || !openConfigExists) {
                            chosenSecurityTypes.add(SECURITY_OWE);
                        } else {
                            chosenSecurityTypes.add(SECURITY_NONE);
                        }
                    } else if (isOpen) {
                        // If an OWE AP is in-range, then use it instead if we have a config for it
                        // or no configs at all.
                        if (oweInRange && (oweConfigExists || !openConfigExists)) {
                            continue;
                        } else {
                            chosenSecurityTypes.add(SECURITY_NONE);
                        }
                    } else {
                        chosenSecurityTypes.addAll(securityTypes);
                    }
                } else {
                    chosenSecurityTypes.addAll(securityTypes);
                    if (isSae) {
                        // If we don't need to choose a single security type for the user to select,
                        // then SAE scans can also match to PSK configs, which will be dynamically
                        // upgraded to SAE by the framework at connection time.
                        chosenSecurityTypes.add(SECURITY_PSK);
                    }
                }

                for (int security : chosenSecurityTypes) {
                    final String key = ssidAndSecurityToStandardWifiEntryKey(ssid, security);
                    if (!scanResultsByKey.containsKey(key)) {
                        scanResultsByKey.put(key, new ArrayList<>());
                    }
                    scanResultsByKey.get(key).add(scan);
                }
            }
        }
        return scanResultsByKey;
    }

    static CharSequence getAppLabel(Context context, String packageName) {
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfoAsUser(
                    packageName,
                    0 /* flags */,
                    UserHandle.getUserId(UserHandle.USER_CURRENT));
            return appInfo.loadLabel(context.getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing.
        }
        return "";
    }

    static CharSequence getAppLabelForSavedNetwork(@NonNull Context context,
            @NonNull WifiEntry wifiEntry) {
        final WifiConfiguration config = wifiEntry.getWifiConfiguration();
        if (context == null || wifiEntry == null || config == null) {
            return "";
        }

        final PackageManager pm = context.getPackageManager();
        final String systemName = pm.getNameForUid(android.os.Process.SYSTEM_UID);
        final int userId = UserHandle.getUserId(config.creatorUid);
        ApplicationInfo appInfo = null;
        if (config.creatorName != null && config.creatorName.equals(systemName)) {
            appInfo = context.getApplicationInfo();
        } else {
            try {
                final IPackageManager ipm = AppGlobals.getPackageManager();
                appInfo = ipm.getApplicationInfo(config.creatorName, 0 /* flags */, userId);
            } catch (RemoteException rex) {
                // Do nothing.
            }
        }
        if (appInfo != null
                && !appInfo.packageName.equals(context.getString(R.string.settings_package))
                && !appInfo.packageName.equals(
                context.getString(R.string.certinstaller_package))) {
            return appInfo.loadLabel(pm);
        } else {
            return "";
        }
    }

    static String getAutoConnectDescription(@NonNull Context context,
            @NonNull WifiEntry wifiEntry) {
        if (context == null || wifiEntry == null || !wifiEntry.isSaved()) {
            return "";
        }

        return wifiEntry.isAutoJoinEnabled()
                ? "" : context.getString(R.string.auto_connect_disable);
    }

    static String getMeteredDescription(@NonNull Context context, @Nullable WifiEntry wifiEntry) {
        final WifiConfiguration config = wifiEntry.getWifiConfiguration();
        if (context == null || wifiEntry == null || config == null) {
            return "";
        }

        if (wifiEntry.getMeteredChoice() == WifiEntry.METERED_CHOICE_METERED) {
            return context.getString(R.string.wifi_metered_label);
        } else if (wifiEntry.getMeteredChoice() == WifiEntry.METERED_CHOICE_UNMETERED) {
            return context.getString(R.string.wifi_unmetered_label);
        } else { // METERED_CHOICE_AUTO
            return wifiEntry.isMetered() ? context.getString(R.string.wifi_metered_label) : "";
        }
    }

    static String getSpeedDescription(@NonNull Context context, @NonNull WifiEntry wifiEntry) {
        // TODO(b/70983952): Fill this method in.
        if (context == null || wifiEntry == null) {
            return "";
        }
        return "";
    }

    static String getVerboseLoggingDescription(@NonNull WifiEntry wifiEntry) {
        if (!BaseWifiTracker.isVerboseLoggingEnabled() || wifiEntry == null) {
            return "";
        }

        final StringJoiner sj = new StringJoiner(" ");

        final String wifiInfoDescription = wifiEntry.getWifiInfoDescription();
        if (!TextUtils.isEmpty(wifiInfoDescription)) {
            sj.add(wifiInfoDescription);
        }

        final String scanResultsDescription = wifiEntry.getScanResultDescription();
        if (!TextUtils.isEmpty(scanResultsDescription)) {
            sj.add(scanResultsDescription);
        }

        return sj.toString();
    }
}