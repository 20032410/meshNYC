package org.servalproject.system;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.servalproject.Control;
import org.servalproject.ServalBatPhoneApplication;
import org.servalproject.servaldna.ServalDCommand;
import org.servalproject.servaldna.ServalDFailureException;
import org.servalproject.servaldna.ServalDInterfaceException;
import org.servalproject.system.bluetooth.BlueToothControl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkManager {
	static final String TAG = "NetworkManager";
	public final WifiControl control;
	public final BlueToothControl blueToothControl;
	private static NetworkManager manager;
	private final ServalBatPhoneApplication app;

	public static synchronized NetworkManager createNetworkManager(ServalBatPhoneApplication app) {
		if (manager == null)
			manager = new NetworkManager(app);
		return manager;
	}

	// merge scan results based on SSID and capabilities
	public Collection<ScanResults> getScanResults(){
		if (!control.wifiManager.isWifiEnabled())
			return null;
		Map<String, ScanResults> newResults = new HashMap<String, ScanResults>();
		List<ScanResult> resultsList = control.wifiManager.getScanResults();
		if (resultsList!=null){
			// build a map of pre-configured access points
			List<WifiConfiguration> configured = control.wifiManager
					.getConfiguredNetworks();
			Map<String, WifiConfiguration> configuredMap = new HashMap<String, WifiConfiguration>();

			if (configured != null) {
				for (WifiConfiguration c : configured) {
					if (c.BSSID!=null && !c.BSSID.equals("")){
						configuredMap.put(c.BSSID, c);
					}else {
						String ssid = c.SSID;
						if (ssid == null)
							continue;
						if (ssid.startsWith("\"") && ssid.endsWith("\""))
							ssid = ssid.substring(1, ssid.length() - 1);
						configuredMap.put(ssid, c);
					}
				}
			}

			for (ScanResult s:resultsList){
				String key = s.SSID+"|"+s.capabilities;
				ScanResults res = newResults.get(key);
				if (res==null){
					res = new ScanResults(s);
					newResults.put(key, res);

					WifiConfiguration c = configuredMap.get(s.SSID);
					if (c!=null){
						configuredMap.remove(s.SSID);
						res.setConfiguration(c);
					}
				}else{
					res.addResult(s);
				}

				WifiConfiguration c = configuredMap.get(s.BSSID);
				if (c!=null){
					configuredMap.remove(s.BSSID);
					res.setConfiguration(c);
				}
			}
		}
		return newResults.values();
	}

	public void onFlightModeChanged(Intent intent) {
		if (intent.getBooleanExtra("state", false))
			control.off(null);
	}

	private NetworkManager(ServalBatPhoneApplication app) {
		this.control = new WifiControl(app);
		BlueToothControl b=null;
		try {
			b = app.server.getBlueToothControl();
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		} catch (ServalDInterfaceException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		this.blueToothControl=b;
		this.app=app;
		networkStateChanged(true);
		blueToothControl.onEnableChanged();
	}

	public InetAddress getAddress() throws SocketException {
		// TODO get actual address from interface
		for (Enumeration<NetworkInterface> interfaces = NetworkInterface
				.getNetworkInterfaces(); interfaces.hasMoreElements();) {
			NetworkInterface i = interfaces.nextElement();
			for (Enumeration<InetAddress> enumIpAddress = i.getInetAddresses();
				 enumIpAddress.hasMoreElements();) {
				InetAddress iNetAddress = enumIpAddress.nextElement();
				if (!iNetAddress.isLoopbackAddress()) {
					// TODO Make sure we don't return cellular interface....
					return iNetAddress;
				}
			}
		}
		return null;
	}

	private boolean isUsableNetworkConnected() {
		if (this.control.wifiManager.isWifiEnabled()) {
			NetworkInfo networkInfo = control.connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
			if (networkInfo==null)
				return false;
			return networkInfo.isConnected();
		}

		if (this.control.wifiApManager != null
				&& this.control.wifiApManager.isWifiApEnabled()) {
			return true;
		}

		if (this.control.adhocControl.getState() == NetworkState.Enabled)
			return true;

		return false;
	}

	public void onEnableChanged(boolean enabled) {
		if (!enabled)
			this.control.turnOffAdhoc();
		onNetworkStateChanged();
		blueToothControl.onEnableChanged();
	}

	private WifiManager.MulticastLock multicastLock = null;

	private WifiManager.MulticastLock getMulticastLock(){
		if (multicastLock == null){
			WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
			multicastLock = wm.createMulticastLock("org.servalproject");
		}
		return multicastLock;
	}

	private void enableWifi(){
		getMulticastLock().acquire();
		try {
			// TODO remove once serval-dna has a netlink socket
			ServalDCommand.configActions(
					ServalDCommand.ConfigAction.del, "interfaces.0.exclude",
					ServalDCommand.ConfigAction.sync
			);
		} catch (ServalDFailureException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void disableWifi(){
		WifiManager.MulticastLock lock = getMulticastLock();
		if (lock.isHeld())
			lock.release();
		try {
			// TODO remove once serval-dna has a netlink socket
			ServalDCommand.configActions(
					ServalDCommand.ConfigAction.set, "interfaces.0.exclude", "on",
					ServalDCommand.ConfigAction.sync
			);
		} catch (ServalDFailureException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private boolean wifiIsUp = false;
	private void networkStateChanged(boolean force){
		boolean wifiOn = isUsableNetworkConnected();
		boolean bluetoothOn = this.blueToothControl.isEnabled();
		boolean enabled = app.isEnabled();

		if (!enabled)
			bluetoothOn = wifiOn = false;

		boolean runService = wifiOn || bluetoothOn;
		boolean serviceRunning =app.controlService!=null;
		if (serviceRunning != runService) {
			Intent serviceIntent = new Intent(app, Control.class);
			if (runService) {
				app.startService(serviceIntent);
			} else {
				app.stopService(serviceIntent);
			}
		}

		if (wifiIsUp!=wifiOn || force) {
			if (wifiOn)
				enableWifi();
			else
				disableWifi();
		}
		wifiIsUp=wifiOn;
	}

	public void onNetworkStateChanged() {
		networkStateChanged(false);
	}
}