package com.startrac.bridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status, metrics, rsc;
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> addresses = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private boolean registered;
    private String pendingAction = BridgeService.ACTION_SCAN;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (BridgeService.BROADCAST_FOUND.equals(intent.getAction())) {
                String address = intent.getStringExtra("address");
                String name = intent.getStringExtra("name");
                if (address != null && !addresses.contains(address)) {
                    addresses.add(address); names.add(name + "  ·  " + address); adapter.notifyDataSetChanged();
                }
            } else if (BridgeService.BROADCAST_STATUS.equals(intent.getAction())) {
                status.setText(intent.getStringExtra("status"));
                double kmh = intent.getDoubleExtra("speed", 0);
                String pace = kmh > 0.1 ? String.format(Locale.US, "%d:%02d", (int)(60/kmh),
                        (int)Math.round((60/kmh - (int)(60/kmh))*60)) + " min/km" : "—";
                metrics.setText(String.format(Locale.US,"%.2f km/h    %s\n%.2f km    %d s de cinta",
                        kmh, pace, intent.getDoubleExtra("distance",0)/1000.0,
                        intent.getLongExtra("seconds",0)));
                rsc.setText(intent.getStringExtra("rsc"));
            }
        }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("STAR TRAC BRIDGE   0.2 β"); title.setTextSize(23);
        title.setGravity(Gravity.CENTER); root.addView(title);
        status = new TextView(this); status.setText("Pulsa «Buscar cintas»."); status.setTextSize(16); root.addView(status);
        metrics = new TextView(this); metrics.setText("— km/h  ·  — min/km"); metrics.setTextSize(22); root.addView(metrics);
        rsc = new TextView(this); rsc.setText("RSC: inactivo"); root.addView(rsc);
        Button search = new Button(this); search.setText("1 · Buscar cintas Star Trac"); root.addView(search);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        ListView list = new ListView(this); list.setAdapter(adapter); root.addView(list,
                new LinearLayout.LayoutParams(-1, 400));
        TextView instruction = new TextView(this);
        instruction.setText("2 · Toca tu cinta en la lista.\n3 · En el Fenix 6 busca un nuevo sensor Foot Pod / velocidad.\n\n" +
            "IMPORTANTE: esta versión retransmite cadencia 0 porque no tenemos una cadencia real. " +
            "Puede que el Garmin no reconozca el sensor RSC o que altere su cadencia. Es una prueba.\n" +
            "No controla ni modifica el movimiento de la cinta.");
        root.addView(instruction);
        Button pause = new Button(this); pause.setText("Pausar / reanudar transmisión"); root.addView(pause);
        Button demo8 = new Button(this);
        demo8.setText("PROBAR SIN CINTA · SIMULAR 8 km/h");root.addView(demo8);
        Button demo10 = new Button(this);
        demo10.setText("PROBAR SIN CINTA · SIMULAR 10 km/h");root.addView(demo10);
        TextView demoWarning = new TextView(this);
        demoWarning.setText("⚠ Modo de prueba: datos INVENTADOS, no grabes una actividad real con el Garmin.");
        root.addView(demoWarning);
        Button stop = new Button(this); stop.setText("Detener puente"); root.addView(stop);
        search.setOnClickListener(v -> { names.clear(); addresses.clear(); adapter.notifyDataSetChanged(); ensurePermissionsAndStart(BridgeService.ACTION_SCAN); });
        list.setOnItemClickListener((parent, view, pos, id) -> send(BridgeService.ACTION_CONNECT, addresses.get(pos)));
        pause.setOnClickListener(v -> send(BridgeService.ACTION_PAUSE, null));
        demo8.setOnClickListener(v -> ensurePermissionsAndStart(BridgeService.ACTION_DEMO8));
        demo10.setOnClickListener(v -> ensurePermissionsAndStart(BridgeService.ACTION_DEMO10));
        stop.setOnClickListener(v -> send(BridgeService.ACTION_STOP, null));
    }
    private void send(String action, String address) {
        Intent intent = new Intent(this, BridgeService.class).setAction(action);
        if (address != null) intent.putExtra("address", address);
        if (BridgeService.ACTION_SCAN.equals(action) || BridgeService.ACTION_DEMO8.equals(action) ||
                BridgeService.ACTION_DEMO10.equals(action)) startForegroundService(intent);
        else startService(intent);
    }
    private void ensurePermissionsAndStart(String action) {
        pendingAction=action;
        BluetoothManager mgr = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetooth = mgr.getAdapter();
        if (bluetooth == null || !bluetooth.isEnabled()) {
            status.setText("Activa Bluetooth en Android y vuelve a pulsar Buscar."); return;
        }
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            for (String s : new String[] {Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}) {
                if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) needed.add(s);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (needed.isEmpty()) send(pendingAction, null);
        else requestPermissions(needed.toArray(new String[0]), 102);
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req != 102) return;
        for (int i=0; i<perms.length; i++) {
            if (grants[i] != PackageManager.PERMISSION_GRANTED &&
                    !Manifest.permission.POST_NOTIFICATIONS.equals(perms[i])) {
                status.setText("Permiso Bluetooth denegado: " + perms[i]); return;
            }
        }
        send(pendingAction, null);
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BridgeService.BROADCAST_STATUS); filter.addAction(BridgeService.BROADCAST_FOUND);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,filter);
        registered = true;
        send(BridgeService.ACTION_STATUS, null);
    }
    @Override protected void onStop() {
        if (registered) { unregisterReceiver(receiver); registered=false; }
        super.onStop();
    }
}
