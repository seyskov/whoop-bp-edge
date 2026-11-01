package org.whoopbp.probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** UI + local transport only. Protocol code comes from our sibling protocol fork. */
public final class ProbeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private final ArrayDeque<byte[]> writes=new ArrayDeque<>();
    private boolean writing,scanning,ready,rawActive,historyActive,disconnecting,notifySetupStarted,retryScheduled;
    private int seq,connectionStatus=-1,notified,notifications,notifyBytes,writeRetries,lastWriteStartCode;
    private byte[] pendingWrite;
    private String phase="idle",error=null;
    private long startedMs=SystemClock.elapsedRealtime();
    private final LinkedHashMap<String,BluetoothDevice> devices=new LinkedHashMap<>();
    private final Map<String,Gen5Probe.Parser> parsers=new LinkedHashMap<>();
    private final Map<Integer,String> flagRequests=new HashMap<>();
    private final Map<String,Object> flagResponses=new LinkedHashMap<>();
    private Gen5Probe.Stats stats=new Gen5Probe.Stats();
    private TextView status,summary;
    private LinearLayout deviceList;
    private final ArrayList<BluetoothGattCharacteristic> notifyChars=new ArrayList<>();
    private final UUID cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private boolean dirty;
    private final Runnable refresh=new Runnable(){public void run(){if(dirty){dirty=false;render();save();}main.postDelayed(this,1000);}};
    private final Runnable rawDeadline=()->stopRaw();
    private final Runnable historyDeadline=()->stopHistory();
    private final Runnable scanDeadline=()->stopScan();
    private final Runnable writeTimeout=()->fail("GATT write timeout; disconnect and reconnect");

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,30,24,16);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        TextView title=new TextView(this);title.setText("WHOOP 5 · проверка сигналов");title.setTextSize(24);root.addView(title);
        TextView info=new TextView(this);info.setText("Давление не вычисляется. Нет интернета, изменения прошивки или постоянных настроек. История не подтверждается к удалению. Закройте WHOOP / Edge перед подключением.\nСырые сигналы — только в RAM. Отчёт содержит счётчики, без пульса и идентификаторов.");root.addView(info);
        status=new TextView(this);status.setTextSize(17);root.addView(status);
        button(root,"1. Найти WHOOP 5",()->scan());
        deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);root.addView(deviceList);
        button(root,"2. Прочитать сведения и флаги",()->survey());
        button(root,"3. Live RAW · 45 секунд",()->startRaw());
        button(root,"4. Первый блок истории · без ACK",()->startHistory());
        button(root,"Остановить / отключиться",()->disconnect());
        summary=new TextView(this);summary.setTypeface(android.graphics.Typeface.MONOSPACE);summary.setTextSize(12);root.addView(summary);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);adapter=manager==null?null:manager.getAdapter();
        main.post(refresh);render();requestPermissionsIfNeeded();
    }
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());parent.addView(b);}
    private boolean allowed(){
        if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestPermissionsIfNeeded(){if(!allowed())requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},1);}
    private void note(String value){phase=value;dirty=true;render();}
    private void fail(String value){error=value;note("error");save();}

    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){main.post(()->{
            addDevice(result.getDevice());
        });}
        @Override public void onScanFailed(int code){main.post(()->fail("BLE scan failed: "+code));}
    };
    private void addDevice(BluetoothDevice device){
        String key=device.getAddress();if(devices.containsKey(key))return;
        devices.put(key,device);Button b=new Button(this);b.setText("Подключить WHOOP · "+devices.size());b.setOnClickListener(v->connect(device));deviceList.addView(b);note("found_"+devices.size());
    }
    private void scan(){
        if(!allowed()){requestPermissionsIfNeeded();return;}if(adapter==null || !adapter.isEnabled()){fail("Включите Bluetooth");return;}
        if(gatt!=null){fail("Сначала отключитесь");return;}stopScan();devices.clear();deviceList.removeAllViews();error=null;
        // The already-paired strap need not advertise again after reconnect.
        // Only WHOOP-named bonds appear, never a list of the user's devices.
        for(BluetoothDevice bonded:adapter.getBondedDevices()){
            String name=bonded.getName();if(name!=null && name.toUpperCase(Locale.ROOT).contains("WHOOP"))addDevice(bonded);
        }
        ScanFilter filter=new ScanFilter.Builder().setServiceUuid(new ParcelUuid(UUID.fromString(Gen5Probe.SERVICE))).build();
        adapter.getBluetoothLeScanner().startScan(Collections.singletonList(filter),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        scanning=true;note("scanning_20s");main.postDelayed(scanDeadline,20000);
    }
    private void stopScan(){main.removeCallbacks(scanDeadline);if(scanning && adapter!=null && allowed()){try{adapter.getBluetoothLeScanner().stopScan(scanCallback);}catch(RuntimeException ignored){}}scanning=false;}
    private void connect(BluetoothDevice device){
        stopScan();if(gatt!=null){fail("Уже подключено / подключается");return;}
        stats=new Gen5Probe.Stats();parsers.clear();flagRequests.clear();flagResponses.clear();notifyChars.clear();writes.clear();pendingWrite=null;writeRetries=0;retryScheduled=false;writing=false;ready=false;disconnecting=false;notifySetupStarted=false;error=null;notified=0;notifications=0;notifyBytes=0;seq=0;startedMs=SystemClock.elapsedRealtime();
        note("connecting");gatt=device.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);
        final BluetoothGatt connectingGatt=gatt;
        main.postDelayed(()->{if(gatt==connectingGatt && !ready)fail("Connection/setup timeout. Close WHOOP and put band in pairing mode.");},30000);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int statusCode,int state){main.post(()->{
            if(g!=gatt)return;connectionStatus=statusCode;
            if(state==BluetoothProfile.STATE_CONNECTED){note("discovering_services");g.discoverServices();}
            else if(state==BluetoothProfile.STATE_DISCONNECTED){ready=false;rawActive=false;historyActive=false;write=null;writing=false;pendingWrite=null;writes.clear();main.removeCallbacks(writeTimeout);g.close();gatt=null;disconnecting=false;note("disconnected");save();}
        });}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){main.post(()->{
            if(g!=gatt)return;if(code!=BluetoothGatt.GATT_SUCCESS){fail("Service discovery status "+code);return;}
            BluetoothGattService service=g.getService(UUID.fromString(Gen5Probe.SERVICE));if(service==null){fail("Not a WHOOP 5 strap service");return;}
            write=service.getCharacteristic(UUID.fromString(Gen5Probe.WRITE));if(write==null){fail("Command characteristic absent");return;}
            for(String id:Gen5Probe.NOTIFY){BluetoothGattCharacteristic c=service.getCharacteristic(UUID.fromString(id));if(c!=null)notifyChars.add(c);}
            if(notifyChars.size()!=3){fail("Expected command/event/data notifications");return;}
            note("requesting_mtu");if(!g.requestMtu(247))beginNotifySetup();
            // A platform that omits the callback must still be usable at the
            // default MTU. A late callback cannot start a second CCCD write.
            main.postDelayed(()->{if(g==gatt)beginNotifySetup();},4000);
        });}
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int code){main.post(()->{if(g==gatt)beginNotifySetup();});}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){main.post(()->{
            if(g!=gatt)return;if(code!=BluetoothGatt.GATT_SUCCESS){fail("Notify subscription status "+code);return;}notified++;enableNextNotify();
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){main.post(()->{
            if(g!=gatt)return;main.removeCallbacks(writeTimeout);writing=false;pendingWrite=null;writeRetries=0;if(code!=BluetoothGatt.GATT_SUCCESS){writes.clear();fail("Command write status "+code);return;}
            main.postDelayed(()->pumpWrites(),160);
        });}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){byte[] b=c.getValue();if(b!=null){byte[] copy=b.clone();main.post(()->receive(g,c.getUuid().toString(),copy));}}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){byte[] copy=value.clone();main.post(()->receive(g,c.getUuid().toString(),copy));}
    };
    private void beginNotifySetup(){if(notifySetupStarted || gatt==null)return;notifySetupStarted=true;enableNextNotify();}
    private void enableNextNotify(){
        if(gatt==null || ready)return;
        if(notified>=notifyChars.size()){
            ready=true;note("connected_readonly");send(Gen5Probe.HELLO,new byte[]{1});return;
        }
        BluetoothGattCharacteristic c=notifyChars.get(notified);BluetoothGattDescriptor d=c.getDescriptor(cccd);
        if(d==null || !gatt.setCharacteristicNotification(c,true)){fail("Cannot subscribe");return;}
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);if(!gatt.writeDescriptor(d))fail("CCCD write refused");
    }
    private void receive(BluetoothGatt source,String uuid,byte[] bytes){
        if(source!=gatt)return;notifications++;notifyBytes+=bytes.length;
        Gen5Probe.Parser parser=parsers.get(uuid);if(parser==null){parser=new Gen5Probe.Parser(in->{stats.accept(in);response(in);});parsers.put(uuid,parser);}parser.feed(bytes);dirty=true;
    }
    private void response(byte[] in){
        if(in.length>=5 && (in[0]&255)==0x24 && (in[2]&255)==Gen5Probe.FLAG){
            String key=flagRequests.get(in[3]&255);if(key!=null){Map<String,Object> entry=new LinkedHashMap<>();entry.put("status",in[4]&255);entry.put("body_bytes",in.length-5);entry.put("value_decoded",false);flagResponses.put(key,entry);}
        }
        // Echo correlation is not assumed reliable; no mutation depends on it.
    }
    private void send(int opcode,byte[] payload){
        if(!ready || gatt==null || write==null){fail("Сначала подключите браслет");return;}
        writes.add(Gen5Probe.command(seq++ & 255,opcode,payload));pumpWrites();
    }
    private void pumpWrites(){
        if(writing || retryScheduled || (pendingWrite==null && writes.isEmpty()) || gatt==null || write==null)return;
        if(pendingWrite==null){pendingWrite=writes.removeFirst();writeRetries=0;}
        writing=true;boolean accepted;
        if(Build.VERSION.SDK_INT>=33){
            lastWriteStartCode=gatt.writeCharacteristic(write,pendingWrite,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            accepted=lastWriteStartCode==BluetoothStatusCodes.SUCCESS;
        }else{
            write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);write.setValue(pendingWrite);
            accepted=gatt.writeCharacteristic(write);lastWriteStartCode=accepted?0:-1;
        }
        if(!accepted){
            writing=false;
            if(++writeRetries<=8){retryScheduled=true;final BluetoothGatt owner=gatt;main.postDelayed(()->{if(gatt==owner){retryScheduled=false;pumpWrites();}},250);return;}
            pendingWrite=null;writes.clear();fail("GATT write refused, start status "+lastWriteStartCode);return;
        }
        main.removeCallbacks(writeTimeout);main.postDelayed(writeTimeout,8000);
    }
    private boolean canProbe(){
        if(!ready || !stats.helloSucceeded){fail("Дождитесь успешного WHOOP 5 HELLO");return false;}
        if(rawActive || historyActive || writing || pendingWrite!=null || !writes.isEmpty()){fail("Дождитесь окончания текущей операции");return false;}error=null;return true;
    }
    private void survey(){
        if(!canProbe())return;note("reading_flags_no_writes");send(Gen5Probe.CLOCK,new byte[0]);send(Gen5Probe.RANGE,new byte[0]);
        for(String name:Gen5Probe.FLAGS){flagRequests.put(seq&255,name);send(Gen5Probe.FLAG,Gen5Probe.flagBody(name));}
        main.postDelayed(()->{if(ready && !rawActive && !historyActive){note("readonly_survey_done");save();}},6000);
    }
    private void startRaw(){
        if(!canProbe())return;rawActive=true;note("live_raw_45s_RAM_only");send(Gen5Probe.RAW_START,Gen5Probe.rawBody(45000));
        main.removeCallbacks(rawDeadline);main.postDelayed(rawDeadline,45000);
    }
    private void stopRaw(){
        main.removeCallbacks(rawDeadline);if(!rawActive)return;rawActive=false;
        if(ready)send(Gen5Probe.RAW_STOP,new byte[]{1});note("raw_capture_done");save();
    }
    private void startHistory(){
        if(!canProbe())return;historyActive=true;note("first_history_burst_no_ACK_12s");send(Gen5Probe.HISTORY,new byte[0]);
        main.removeCallbacks(historyDeadline);main.postDelayed(historyDeadline,12000);
    }
    private void stopHistory(){
        main.removeCallbacks(historyDeadline);if(!historyActive)return;historyActive=false;
        if(ready)send(Gen5Probe.ABORT,new byte[]{0});note("history_preview_done_no_trim_ACK");save();
    }
    private void disconnect(){
        stopScan();if(gatt==null){note("disconnected");save();return;}if(disconnecting)return;
        disconnecting=true;stopRaw();stopHistory();final BluetoothGatt old=gatt;
        // Let explicit STOP/ABORT reach the wire before disconnecting. The raw
        // command also has a strap-side 45s deadline if Android kills us.
        main.postDelayed(()->{if(gatt==old){old.disconnect();main.postDelayed(()->{if(gatt==old){old.close();gatt=null;ready=false;write=null;writing=false;writes.clear();note("disconnected");save();}},2000);}},1500);
    }
    private Map<String,Object> report(){
        Map<String,Object> r=stats.report();r.put("phase",phase);r.put("error",error);r.put("connection_status",connectionStatus);
        r.put("elapsed_seconds",(SystemClock.elapsedRealtime()-startedMs)/1000);r.put("notification_count",notifications);r.put("notification_bytes",notifyBytes);r.put("last_write_start_code",lastWriteStartCode);
        r.put("flag_read_responses",flagResponses);
        int header=0,payload=0,unknown=0,pending=0;for(Gen5Probe.Parser p:parsers.values()){header+=p.badHeaders;payload+=p.badPayloads;unknown+=p.unknownRevision;pending+=p.pendingBytes();}
        r.put("crc_header_failures",header);r.put("crc_payload_failures",payload);r.put("unknown_frame_revisions",unknown);r.put("partial_frame_bytes",pending);return r;
    }
    private void save(){
        try{String json=new JSONObject(report()).toString(2);File tmp=new File(getFilesDir(),"report.tmp");
            try(FileOutputStream f=new FileOutputStream(tmp)){f.write(json.getBytes("UTF-8"));}
            if(!tmp.renameTo(new File(getFilesDir(),"report.json")))error="Could not atomically save summary";
        }catch(Exception e){error="Could not save summary: "+e.getClass().getSimpleName();}
    }
    private void render(){
        if(status==null)return;status.setText("\nЭтап: "+phase+(error==null?"":"\n"+error)+"\n");
        try{summary.setText(new JSONObject(report()).toString(2));}catch(Exception e){summary.setText("Summary unavailable");}
    }
    @Override protected void onPause(){stopRaw();stopHistory();save();super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);stopScan();if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}super.onDestroy();}
}
