package com.startrac.bridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proof-of-concept BLE central (Star Trac GymConnect) + peripheral (RSC).
 * Read-only towards treadmill: no control or change of speed/incline.
 */
public class BridgeService extends Service {
    static final String ACTION_SCAN = "com.startrac.bridge.SCAN";
    static final String ACTION_CONNECT = "com.startrac.bridge.CONNECT";
    static final String ACTION_PAUSE = "com.startrac.bridge.PAUSE";
    static final String ACTION_DEMO8 = "com.startrac.bridge.DEMO8";
    static final String ACTION_DEMO10 = "com.startrac.bridge.DEMO10";
    static final String ACTION_STOP = "com.startrac.bridge.STOP";
    static final String ACTION_STATUS = "com.startrac.bridge.STATUS_REQUEST";
    static final String BROADCAST_STATUS = "com.startrac.bridge.STATUS";
    static final String BROADCAST_FOUND = "com.startrac.bridge.FOUND";
    private static final UUID GYM_SERVICE = UUID.fromString("a026ee07-0a7d-4ab3-97fa-f1500f9feb8b");
    private static final UUID GYM_MEASUREMENT = UUID.fromString("a026e01d-0a7d-4ab3-97fa-f1500f9feb8b");
    private static final UUID RSC_SERVICE = uuid16(0x1814);
    private static final UUID RSC_MEASUREMENT = uuid16(0x2A53);
    private static final UUID RSC_FEATURE = uuid16(0x2A54);
    private static final UUID SENSOR_LOCATION = uuid16(0x2A5D);
    private static final UUID SC_CONTROL_POINT = uuid16(0x2A55);
    private static final UUID DEVICE_INFORMATION = uuid16(0x180A);
    private static final UUID MANUFACTURER_NAME = uuid16(0x2A29);
    private static final UUID MODEL_NUMBER = uuid16(0x2A24);
    private static final String LOG_TAG = "StarTracBridge";
    private static final UUID CCCD = uuid16(0x2902);
    private static UUID uuid16(int v) { return UUID.fromString(String.format(Locale.US,
            "0000%04x-0000-1000-8000-00805f9b34fb",v)); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt treadmill;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic measurement;
    private BluetoothGattCharacteristic controlPoint;
    private final Set<String> connectedGarmins = ConcurrentHashMap.newKeySet();
    private final Set<String> controlPointIndications = ConcurrentHashMap.newKeySet();
    private double rscDistanceOffset;
    private String diagnostic = "Esperando al Garmin";
    private int eventNumber;
    private boolean scanning, advertising, foreground, paused, demo;
    private double demoSpeed, demoDistance, pauseDistance, pauseOffset;
    private long demoStarted, lastDemoTick;
    private final Set<String> rscClients = ConcurrentHashMap.newKeySet();
    private String status = "Sin conectar";
    private String rscStatus = "RSC no iniciado";
    private GymConnectDecoder.Reading latest;
    private long lastUpdateUptime, baseMeters=-1, lastOriginalMeters=-1;
    private final Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());

    private void diagnosticEvent(String message) {
        eventNumber++;
        diagnostic = "#" + eventNumber + " " + message;
        Log.i(LOG_TAG, diagnostic);
        sendStatus();
    }
    private void updateRscStatus() {
        rscStatus = "BLE RSC: " + (advertising ? "anunciando" : "no anuncia")
                + " · enlaces: " + connectedGarmins.size()
                + " · suscripciones: " + rscClients.size()
                + " · SC: " + controlPointIndications.size();
        sendStatus();
    }
    @Override public void onCreate() {
        super.onCreate();
        manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        main.post(tick);
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        String a = intent.getAction();
        if (ACTION_SCAN.equals(a)) {
            demo=false;latest=null;pauseOffset=0;paused=false;
            startAsForeground();
            startPeripheralIfPossible();
            scan();
        } else if (ACTION_CONNECT.equals(a)) {
            connect(intent.getStringExtra("address"));
        } else if (ACTION_DEMO8.equals(a) || ACTION_DEMO10.equals(a)) {
            startAsForeground();startPeripheralIfPossible();stopScan();
            long now=SystemClock.elapsedRealtime();
            if (!demo) { demoDistance=0;demoStarted=now;pauseOffset=0; }
            if (!demo) rscDistanceOffset = 0;
            demo=true;demoSpeed=ACTION_DEMO8.equals(a) ? 8.0 : 10.0;
            lastDemoTick=now;paused=false;
            status="⚠ DEMO: velocidad SIMULADA (no correr ni grabar esta actividad)";sendStatus();
        } else if (ACTION_PAUSE.equals(a)) {
            if (!paused) {
                pauseDistance=currentDistance();paused=true;
            } else {
                if (!demo && latest!=null) {
                    double absolute=Math.max(0,latest.distanceMeters-baseMeters);
                    pauseOffset=Math.max(0,absolute-pauseDistance);
                }
                paused=false;
            }
            status = paused ? "Retransmisión pausada (no pausa la cinta ni el reloj)" : "Retransmisión reanudada";
            sendStatus();
        } else if (ACTION_STOP.equals(a)) {
            stopSelf();
        } else if (ACTION_STATUS.equals(a)) sendStatus();
        return foreground ? START_STICKY : START_NOT_STICKY;
    }
    private boolean permissions() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }
    private void startAsForeground() {
        if (foreground) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("bridge", "Star Trac Bridge", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this,"bridge")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Star Trac Bridge")
            .setContentText("Conexión Bluetooth activa. Abre la app para detenerla.")
            .setOngoing(true).build();
        startForeground(77,n);
        foreground = true;
    }
    private void scan() {
        if (adapter == null || !adapter.isEnabled() || !permissions()) {
            status = "Activa Bluetooth y concede los permisos"; sendStatus(); return;
        }
        try {
            stopScan(); seen.clear();
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { status="Android no puede escanear BLE";sendStatus();return; }
            scanner.startScan(scanCallback); scanning=true;
            status = "Buscando cintas durante 15 segundos. Pulsa Bluetooth en su consola.";
            sendStatus();
            main.postDelayed(() -> { stopScan(); sendStatus(); }, 15000);
        } catch (SecurityException | IllegalStateException e) {
            status = "Error al buscar: " + e.getMessage();sendStatus();
        }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            if (result == null) return;
            try {
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                if (name == null) name = result.getDevice().getName();
                if (name == null || !name.toUpperCase(Locale.ROOT).contains("TREADMILL")) return;
                String address = result.getDevice().getAddress();
                if (!seen.add(address)) return;
                Intent b = new Intent(BROADCAST_FOUND).setPackage(getPackageName());
                b.putExtra("address",address); b.putExtra("name",name);
                sendBroadcast(b);
            } catch (SecurityException ignored) { }
        }
        @Override public void onScanFailed(int error) {
            scanning=false; status="Error escaneo BLE: " + error; sendStatus();
        }
    };
    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) { }
        }
        scanning=false;
    }
    private void connect(String address) {
        if (address == null || adapter == null || !permissions()) return;
        stopScan();
        if (treadmill != null) { treadmill.disconnect(); treadmill.close(); treadmill=null; }
        latest=null; baseMeters=-1; lastOriginalMeters=-1; paused=false; demo=false;pauseOffset=0; rscDistanceOffset=0;
        try {
            status="Conectando con cinta " + address; sendStatus();
            BluetoothDevice device=adapter.getRemoteDevice(address);
            treadmill=device.connectGatt(this,false,treadmillCallback,BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { status="Error al conectar: " + e.getMessage(); sendStatus(); }
    }
    private final BluetoothGattCallback treadmillCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int state) {
            if (treadmill != g) return;
            if (statusCode == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                status="Conectada; buscando GymConnect..."; sendStatus();
                try { g.discoverServices(); } catch (SecurityException e) { status=e.toString();sendStatus(); }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                latest=null;
                status="Cinta desconectada (GATT " + statusCode + "). Pulsa Buscar para reconectar.";
                sendStatus();
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int code) {
            if (g != treadmill || code != BluetoothGatt.GATT_SUCCESS) {
                status="No se pudieron descubrir los servicios de la cinta"; sendStatus(); return;
            }
            BluetoothGattService service=g.getService(GYM_SERVICE);
            BluetoothGattCharacteristic c=service == null ? null : service.getCharacteristic(GYM_MEASUREMENT);
            BluetoothGattDescriptor cccd=c == null ? null : c.getDescriptor(CCCD);
            if (cccd == null) { status="Esta consola no ofrece GymConnect A026E01D";sendStatus();return; }
            try {
                if (!g.setCharacteristicNotification(c,true)) {
                    status="Error al habilitar notificaciones GymConnect";sendStatus();return;
                }
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(cccd); }
                status="GymConnect conectado; esperando mediciones";sendStatus();
            } catch (SecurityException e) { status="No se pudo activar Notify: "+e.getMessage();sendStatus(); }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (GYM_MEASUREMENT.equals(c.getUuid())) acceptReading(c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,byte[] value) {
            if (GYM_MEASUREMENT.equals(c.getUuid())) acceptReading(value);
        }
    };
    private void acceptReading(byte[] packet) {
        GymConnectDecoder.Reading data=GymConnectDecoder.decode(packet);
        if (data == null) return;
        if (lastOriginalMeters >= 0 && data.distanceMeters < lastOriginalMeters) {
            baseMeters=data.distanceMeters;pauseOffset=0;
        }
        if (baseMeters < 0) baseMeters=data.distanceMeters;
        latest=data;lastOriginalMeters=data.distanceMeters;
        lastUpdateUptime=SystemClock.elapsedRealtime();
        status="Star Trac conectada · GymConnect recibe datos";
        sendStatus();
    }
    private double currentSpeed() {
        if (paused) return 0;
        if (demo) return demoSpeed;
        if (latest==null || SystemClock.elapsedRealtime()-lastUpdateUptime>4000) return 0;
        return latest.speedKmh;
    }
    private double currentDistance() {
        if (paused) return pauseDistance;
        if (demo) return demoDistance;
        return latest == null ? 0 : Math.max(0,latest.distanceMeters-baseMeters-pauseOffset);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (demo && !paused) {
                long now=SystemClock.elapsedRealtime();
                demoDistance+=demoSpeed/3.6 * Math.min(5.0,Math.max(0,now-lastDemoTick)/1000.0);
                lastDemoTick=now;
            } else if (demo) lastDemoTick=SystemClock.elapsedRealtime();
            if (latest != null || demo) {
                if (!demo && SystemClock.elapsedRealtime()-lastUpdateUptime>4000 && !paused)
                    status="Sin mediciones recientes: enviando velocidad 0 (sin inventar distancia)";
                broadcastRsc();
                sendStatus();
            }
            main.postDelayed(this,1000);
        }
    };
    private void startPeripheralIfPossible() {
        if (server != null || adapter == null || !permissions()) return;
        try {
            if (!adapter.isMultipleAdvertisementSupported()) {
                rscStatus="Este móvil NO admite anunciar sensores BLE";sendStatus();return;
            }
            advertiser=adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { rscStatus="No hay BluetoothLeAdvertiser";sendStatus();return; }
            server=manager.openGattServer(this, serverCallback);
            if (server == null) { rscStatus="No se puede iniciar servidor GATT";sendStatus();return; }
            BluetoothGattService svc=new BluetoothGattService(RSC_SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            measurement=new BluetoothGattCharacteristic(RSC_MEASUREMENT,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            measurement.addDescriptor(new BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
            BluetoothGattCharacteristic features=new BluetoothGattCharacteristic(RSC_FEATURE,
                    BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ);
            // Bit 1: total distance, bit 2: walk/run state. Stride length not provided.
            features.setValue(new byte[]{0x06,0x00});
            BluetoothGattCharacteristic location=new BluetoothGattCharacteristic(SENSOR_LOCATION,
                    BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ);
            location.setValue(new byte[]{0x00}); // other/unknown sensor location
            // RSCS requires SC Control Point when Total Distance is supported
            // (RSC Feature bit 1 = 1). Version 0.1 omitted this mandatory characteristic.
            controlPoint = new BluetoothGattCharacteristic(SC_CONTROL_POINT,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            controlPoint.addDescriptor(new BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
            svc.addCharacteristic(measurement);
            svc.addCharacteristic(features);
            svc.addCharacteristic(location);
            svc.addCharacteristic(controlPoint);
            if (!server.addService(svc)) { rscStatus="No se pudo añadir servicio RSC";sendStatus(); }
            else { rscStatus="Iniciando servidor RSC (con SC Control Point)...";sendStatus(); }
        } catch (Exception e) { rscStatus="Error al crear servidor RSC: "+e.getMessage();sendStatus(); }
    }
    private void startAdvertising() {
        if (advertiser == null || advertising) return;
        try {
            AdvertiseSettings settings=new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true).setTimeout(0).build();
            AdvertiseData data=new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(RSC_SERVICE)).setIncludeDeviceName(false).build();
            AdvertiseData scanResponse = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
            advertiser.startAdvertising(settings,data,scanResponse,advertiseCallback);
            diagnosticEvent("Publicando RSC 0x1814 + Device Information");
        } catch (Exception e) { rscStatus="Error anunciando RSC: "+e.getMessage();sendStatus(); }
    }
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            advertising=true;
            diagnosticEvent("Publicidad BLE RSC activada");
            updateRscStatus();
        }
        @Override public void onStartFailure(int code) {
            advertising=false;
            diagnosticEvent("Error publicidad BLE: " + code);
            updateRscStatus();
        }
    };
    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int statusCode,BluetoothGattService service) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                diagnosticEvent("Error publicando " + service.getUuid() + " GATT " + statusCode);
                if (DEVICE_INFORMATION.equals(service.getUuid())) main.post(() -> startAdvertising());
                return;
            }
            if (RSC_SERVICE.equals(service.getUuid())) {
                diagnosticEvent("Servicio RSC publicado; añadiendo identificación");
                BluetoothGattService dis = new BluetoothGattService(DEVICE_INFORMATION,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY);
                BluetoothGattCharacteristic manufacturer = new BluetoothGattCharacteristic(MANUFACTURER_NAME,
                        BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
                manufacturer.setValue("StarTracBridge".getBytes(StandardCharsets.UTF_8));
                BluetoothGattCharacteristic model = new BluetoothGattCharacteristic(MODEL_NUMBER,
                        BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
                model.setValue("Virtual RSC 0.2".getBytes(StandardCharsets.UTF_8));
                dis.addCharacteristic(manufacturer); dis.addCharacteristic(model);
                try {
                    if (!server.addService(dis)) main.post(() -> startAdvertising());
                } catch (Exception e) { diagnosticEvent("DIS opcional: " + e.getMessage()); main.post(() -> startAdvertising()); }
            } else if (DEVICE_INFORMATION.equals(service.getUuid())) {
                main.post(() -> startAdvertising());
            }
        }
        @Override public void onConnectionStateChange(BluetoothDevice d,int statusCode,int newState) {
            String address = d.getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedGarmins.add(address);
                diagnosticEvent("Garmin: enlace BLE establecido (GATT " + statusCode + ")");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedGarmins.remove(address); rscClients.remove(address); controlPointIndications.remove(address);
                diagnosticEvent("Garmin: desconectado (GATT " + statusCode + ")");
            } else diagnosticEvent("Garmin: estado " + newState + ", GATT " + statusCode);
            updateRscStatus();
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice d,int requestId,
                BluetoothGattDescriptor desc,boolean prepared,boolean response,int offset,byte[] value) {
            int result=BluetoothGatt.GATT_FAILURE;
            UUID chUuid=desc.getCharacteristic().getUuid();
            if (CCCD.equals(desc.getUuid()) && offset==0 && !prepared && value!=null && value.length==2) {
                if (RSC_MEASUREMENT.equals(chUuid) && value[0]==1 && value[1]==0) {
                    rscClients.add(d.getAddress()); result=BluetoothGatt.GATT_SUCCESS;
                    diagnosticEvent("Garmin: Notify RSC activado (velocidad disponible)");
                } else if (RSC_MEASUREMENT.equals(chUuid) && value[0]==0 && value[1]==0) {
                    rscClients.remove(d.getAddress()); result=BluetoothGatt.GATT_SUCCESS;
                    diagnosticEvent("Garmin: Notify RSC desactivado");
                } else if (SC_CONTROL_POINT.equals(chUuid) && value[0]==2 && value[1]==0) {
                    controlPointIndications.add(d.getAddress()); result=BluetoothGatt.GATT_SUCCESS;
                    diagnosticEvent("Garmin: indicaciones del punto de control activadas");
                } else if (SC_CONTROL_POINT.equals(chUuid) && value[0]==0 && value[1]==0) {
                    controlPointIndications.remove(d.getAddress()); result=BluetoothGatt.GATT_SUCCESS;
                }
            }
            if (response && server!=null) try { server.sendResponse(d,requestId,result,offset,null); }
                catch (SecurityException ignored) { }
            updateRscStatus();
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice d,int requestId,int offset,
                BluetoothGattDescriptor desc) {
            UUID chUuid=desc.getCharacteristic().getUuid();
            boolean enabled = RSC_MEASUREMENT.equals(chUuid) ? rscClients.contains(d.getAddress()) :
                    SC_CONTROL_POINT.equals(chUuid) && controlPointIndications.contains(d.getAddress());
            byte[] cccd = enabled ? (SC_CONTROL_POINT.equals(chUuid) ?
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            diagnosticEvent("Garmin: lectura CCCD de " + chUuid.toString().substring(4,8));
            sendRead(d,requestId,offset,cccd);
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice d,int requestId,int offset,
                BluetoothGattCharacteristic charac) {
            diagnosticEvent("Garmin: lectura " + charac.getUuid().toString().substring(4,8));
            byte[] value=charac.getValue();
            sendRead(d,requestId,offset,value==null?new byte[0]:value);
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice d,int requestId,
                BluetoothGattCharacteristic c,boolean prepared,boolean response,int offset,byte[] value) {
            if (!SC_CONTROL_POINT.equals(c.getUuid())) {
                if (response && server != null) server.sendResponse(d,requestId,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,offset,null);
                return;
            }
            // SC Control Point OpCode 0x01: Set Cumulative Value, uint32 in metres.
            boolean valid = !prepared && offset==0 && value!=null && value.length==5 && value[0]==1;
            int result = valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
            if (valid) {
                long distance = ((long)value[1] & 255) | (((long)value[2] & 255)<<8)
                        | (((long)value[3] & 255)<<16) | (((long)value[4] & 255)<<24);
                rscDistanceOffset = distance - currentDistance();
                diagnosticEvent("Garmin: fijó distancia acumulada a " + distance + " m");
            } else diagnosticEvent("Garmin: solicitud SC no soportada");
            if (response && server!=null) try { server.sendResponse(d,requestId,result,offset,null); }
                catch (SecurityException ignored) { }
            // SC Control Point Response Code (0x10), request OpCode (0x01), status success (0x01).
            if (controlPointIndications.contains(d.getAddress()) && server != null) {
                final byte[] reply = new byte[]{0x10,(byte)(valid ? 0x01 : (value!=null && value.length>0 ? value[0]:0)),
                        (byte)(valid ? 0x01 : 0x02)};
                main.postDelayed(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= 33)
                            server.notifyCharacteristicChanged(d,controlPoint,true,reply);
                        else { controlPoint.setValue(reply);server.notifyCharacteristicChanged(d,controlPoint,true); }
                    } catch (Exception e) { diagnosticEvent("Error respuesta SC: " + e.getMessage()); }
                },100);
            }
        }
    };
    private void sendRead(BluetoothDevice device,int id,int offset,byte[] data) {
        if (server==null) return;
        int code=offset<=data.length ? BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_INVALID_OFFSET;
        byte[] response=offset<=data.length ? java.util.Arrays.copyOfRange(data,offset,data.length):null;
        try { server.sendResponse(device,id,code,offset,response); } catch (SecurityException ignored) { }
    }
    private void broadcastRsc() {
        if (server==null || measurement==null || rscClients.isEmpty() || adapter==null) return;
        byte[] bytes=RscCodec.encode(currentSpeed(),Math.max(0,currentDistance()+rscDistanceOffset));
        for (String address:rscClients) {
            try {
                BluetoothDevice device=adapter.getRemoteDevice(address);
                if (Build.VERSION.SDK_INT >= 33) server.notifyCharacteristicChanged(device,measurement,false,bytes);
                else { measurement.setValue(bytes); server.notifyCharacteristicChanged(device,measurement,false); }
            } catch (Exception e) { rscStatus="Error enviando RSC: "+e.getMessage();sendStatus(); }
        }
    }
    private void sendStatus() {
        Intent b=new Intent(BROADCAST_STATUS).setPackage(getPackageName());
        b.putExtra("status",status);
        b.putExtra("speed",currentSpeed());
        b.putExtra("distance",currentDistance());
        b.putExtra("seconds",demo ? (SystemClock.elapsedRealtime()-demoStarted)/1000 :
                (latest==null ? 0 : latest.elapsedSeconds));
        b.putExtra("rsc",rscStatus + "\nÚltimo evento: " + diagnostic
                + "\nCadencia transmitida: 0 (experimental)");
        sendBroadcast(b);
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopScan();
        if (treadmill!=null) { try { treadmill.disconnect(); treadmill.close(); } catch (Exception ignored) { } treadmill=null; }
        if (advertiser!=null) try { advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) { }
        if (server!=null) try { server.clearServices(); server.close(); } catch (Exception ignored) { }
        rscClients.clear();connectedGarmins.clear();controlPointIndications.clear();
        status="Puente detenido";rscStatus="RSC desactivado";latest=null;demo=false;sendStatus();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
