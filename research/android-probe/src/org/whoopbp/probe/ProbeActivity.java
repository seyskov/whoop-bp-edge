package org.whoopbp.probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

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
    private String captureId=UUID.randomUUID().toString();
    private final ExecutorService archiveExecutor=Executors.newSingleThreadExecutor();
    private boolean archivePending;
    private int archivedWindows;
    private long lastHelloMs=-1,lastArchiveMs;
    private WaveView waveView;
    private Map<String,Object> liveShapeAnalysis;
    private boolean liveAnalysisPending;
    private TextView status,summary;
    private LinearLayout deviceList;
    private final ArrayList<BluetoothGattCharacteristic> notifyChars=new ArrayList<>();
    private final UUID cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private boolean dirty;
    private final Runnable refresh=new Runnable(){public void run(){if(dirty){dirty=false;render();save();}if(stats.waveAudit.size()>archivedWindows && SystemClock.elapsedRealtime()-lastArchiveMs>10000)saveWaves();main.postDelayed(this,1000);}};
    private final Runnable rawDeadline=()->stopRaw();
    private final Runnable historyDeadline=()->stopHistory();
    private final Runnable auditHelloTick=new Runnable(){public void run(){if(!historyActive)return;if(ready && !writing && pendingWrite==null && writes.isEmpty())send(Gen5Probe.HELLO,new byte[]{1});main.postDelayed(this,15000);}};
    private final Runnable scanDeadline=()->stopScan();
    private final Runnable writeTimeout=()->fail("GATT write timeout; disconnect and reconnect");

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,30,24,16);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        TextView title=new TextView(this);title.setText("WHOOP 5 · проверка сигналов");title.setTextSize(24);root.addView(title);
        TextView info=new TextView(this);info.setText("Давление не вычисляется. Нет интернета, прошивки и постоянных настроек. История не подтверждается к удалению.\nR26 и совпадающие R18 сохраняются только локально для формы волны. Live-пакеты остаются в RAM. Данные не публикуются.");root.addView(info);
        status=new TextView(this);status.setTextSize(17);root.addView(status);
        button(root,"1. Найти WHOOP 5",()->scan());
        deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);root.addView(deviceList);
        button(root,"2. Прочитать сведения и флаги",()->survey());
        button(root,"3. Live RAW · 45 секунд",()->startRaw());
        button(root,"4. Форма R26 · 2 минуты",()->startHistory());
        button(root,"Сохранить окна локально",()->saveWaves());
        button(root,"Остановить / отключиться",()->disconnect());
        waveView=new WaveView(this);root.addView(waveView,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,280));
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
        stats=new Gen5Probe.Stats();captureId=UUID.randomUUID().toString();liveShapeAnalysis=null;liveAnalysisPending=false;archivedWindows=0;lastHelloMs=-1;lastArchiveMs=0;parsers.clear();flagRequests.clear();flagResponses.clear();notifyChars.clear();writes.clear();pendingWrite=null;writeRetries=0;retryScheduled=false;writing=false;ready=false;disconnecting=false;notifySetupStarted=false;error=null;notified=0;notifications=0;notifyBytes=0;seq=0;startedMs=SystemClock.elapsedRealtime();
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
        Gen5Probe.Parser parser=parsers.get(uuid);if(parser==null){parser=new Gen5Probe.Parser(in->{stats.accept(in,SystemClock.elapsedRealtimeNanos());response(in);});parsers.put(uuid,parser);}parser.feed(bytes);dirty=true;
    }
    private void response(byte[] in){
        if(in.length>=109 && (in[0]&255)==0x24 && (in[2]&255)==Gen5Probe.HELLO && in[4]==1)lastHelloMs=SystemClock.elapsedRealtime();
        if(in.length>=5 && (in[0]&255)==0x24 && (in[2]&255)==Gen5Probe.FLAG){
            String key=flagRequests.get(in[3]&255);if(key!=null){Map<String,Object> entry=new LinkedHashMap<>();entry.put("status",in[4]&255);entry.put("body_bytes",in.length-5);entry.put("value_decoded",false);
                // Revision + echoed 32-byte key + 32-byte value. Read only
                // when BOTH the requested name and a single ASCII boolean
                // value match; never turn stale/unpopulated bytes into a value.
                if(in.length>=70 && in[4]==1 && in[5]==1){
                    String echoed=readAscii32(in,6),value=readAscii32(in,38);
                    if(key.equals(echoed) && ("0".equals(value)||"1".equals(value)||"2".equals(value))){entry.put("value_decoded",true);entry.put("value",value);}
                }
                flagResponses.put(key,entry);}
        }
        // Echo correlation is not assumed reliable; no mutation depends on it.
    }
    private String readAscii32(byte[] in,int offset){
        StringBuilder out=new StringBuilder();boolean ended=false;
        for(int i=0;i<32;i++){int b=in[offset+i]&255;if(b==0){ended=true;continue;}if(ended || b<32 || b>126)return "";out.append((char)b);}return out.toString();
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
        if(rawActive || historyActive || liveAnalysisPending || writing || pendingWrite!=null || !writes.isEmpty()){fail("Дождитесь окончания текущей операции");return false;}error=null;return true;
    }
    private void survey(){
        if(!canProbe())return;note("reading_flags_no_writes");send(Gen5Probe.HELLO,new byte[]{1});send(Gen5Probe.CLOCK,new byte[0]);send(Gen5Probe.RANGE,new byte[0]);
        for(String name:Gen5Probe.FLAGS){flagRequests.put(seq&255,name);send(Gen5Probe.FLAG,Gen5Probe.flagBody(name));}
        main.postDelayed(()->{if(ready && !rawActive && !historyActive){note("readonly_survey_done");save();}},6000);
    }
    private void startRaw(){
        if(!canProbe())return;rawActive=true;stats.rawStartStatus=-1;stats.rawStopStatus=-1;stats.liveAudit=new LiveOpticalAudit();liveShapeAnalysis=null;liveAnalysisPending=false;note("optical_live_plus_raw_45s_RAM_only");send(Gen5Probe.REALTIME_HR,new byte[]{1});send(Gen5Probe.OPTICAL_LIVE,new byte[]{1,1});send(Gen5Probe.RAW_START,Gen5Probe.rawBody(45000));
        main.removeCallbacks(rawDeadline);main.postDelayed(rawDeadline,45000);
    }
    private void stopRaw(){
        main.removeCallbacks(rawDeadline);if(!rawActive)return;rawActive=false;
        if(ready){send(Gen5Probe.RAW_STOP,new byte[]{1});send(Gen5Probe.OPTICAL_LIVE,new byte[]{1,0});send(Gen5Probe.REALTIME_HR,new byte[]{0});}note("raw_capture_done");liveAnalysisPending=true;final String owner=captureId;main.postDelayed(()->{if(owner.equals(captureId))analyzeLive();},700);save();
    }
    private void startHistory(){
        if(!canProbe())return;historyActive=true;note("historical_wave_audit_120s_no_ACK");send(Gen5Probe.HELLO,new byte[]{1});send(Gen5Probe.HISTORY,new byte[0]);
        main.removeCallbacks(historyDeadline);main.postDelayed(historyDeadline,120000);main.removeCallbacks(auditHelloTick);main.postDelayed(auditHelloTick,15000);
    }
    private void stopHistory(){
        main.removeCallbacks(historyDeadline);main.removeCallbacks(auditHelloTick);if(!historyActive)return;historyActive=false;
        if(ready)send(Gen5Probe.ABORT,new byte[]{0});note("wave_audit_done_no_trim_ACK");saveWaves();save();
    }
    private void disconnect(){
        stopScan();saveWaves();if(gatt==null){note("disconnected");save();return;}if(disconnecting)return;
        disconnecting=true;stopRaw();stopHistory();final BluetoothGatt old=gatt;
        // Let explicit STOP/ABORT reach the wire before disconnecting. The raw
        // command also has a strap-side 45s deadline if Android kills us.
        main.postDelayed(()->{if(gatt==old){old.disconnect();main.postDelayed(()->{if(gatt==old){old.close();gatt=null;ready=false;write=null;writing=false;writes.clear();note("disconnected");save();}},2000);}},1500);
    }
    private Map<String,Object> report(){
        Map<String,Object> r=stats.report();r.put("phase",phase);r.put("error",error);r.put("connection_status",connectionStatus);
        r.put("schema",2);r.put("capture_id",captureId);r.put("live_samples_persisted",false);r.put("raw_samples_persisted",archivedWindows>0);r.put("historical_windows_saved",archivedWindows);r.put("archive_pending",archivePending);
        r.put("last_hello_age_seconds",lastHelloMs<0?null:(SystemClock.elapsedRealtime()-lastHelloMs)/1000.0);
        r.put("live_analysis_pending",liveAnalysisPending);r.put("live_shape_analysis",liveShapeAnalysis);
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
        try{summary.setText(new JSONObject(report()).toString(2));}catch(Exception e){summary.setText("Summary unavailable");}if(waveView!=null)waveView.invalidate();
    }
    private void saveWaves(){
        if(archivePending || stats.waveAudit.size()==0)return;archivePending=true;
        final String owner=captureId;final int count=stats.waveAudit.size();final Map<String,Object> snapshot=stats.waveAudit.export();snapshot.put("capture_id",owner);
        archiveExecutor.execute(()->{boolean ok=false;try{
            File tmp=new File(getFilesDir(),"historical-windows.tmp");try(FileOutputStream f=new FileOutputStream(tmp)){f.write(new JSONObject(snapshot).toString().getBytes("UTF-8"));}
            ok=tmp.renameTo(new File(getFilesDir(),"historical-windows.json"));
        }catch(Exception ignored){}final boolean saved=ok;
        main.post(()->{archivePending=false;if(owner.equals(captureId)){if(saved){archivedWindows=count;lastArchiveMs=SystemClock.elapsedRealtime();}else error="Local waveform save failed";dirty=true;}});
        });
    }
    private void analyzeLive(){
        final String owner=captureId;final LiveOpticalAudit snapshot=stats.liveAudit.snapshot();
        archiveExecutor.execute(()->{Map<String,Object> result=snapshot.analyze();main.post(()->{if(owner.equals(captureId)){liveShapeAnalysis=result;liveAnalysisPending=false;dirty=true;save();}});});
    }
    private final class WaveView extends View {
        final Paint paint=new Paint(3);
        WaveView(Context c){super(c);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.drawColor(Color.rgb(244,246,250));paint.setColor(Color.DKGRAY);paint.setTextSize(26);
            List<int[]> live=stats.liveAudit.previewWindows();
            if(!live.isEmpty()){
                canvas.drawText("LIVE R20: separate buffers, RAM only",12,32,paint);int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,total=0;
                for(int[] a:live)for(int n:a){min=Math.min(min,n);max=Math.max(max,n);}for(int[] a:live)total+=a.length;if(max==min)max=min+1;
                int baseSample=0;for(int j=0;j<live.size();j++){int[] a=live.get(j);Path p=new Path();for(int i=0;i<a.length;i++){float x=12+(baseSample+i)/(float)total*(getWidth()-24);float y=getHeight()-18-(a[i]-min)/(float)(max-min)*(getHeight()-75);if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}paint.setColor(j%2==0?Color.rgb(28,83,180):Color.rgb(0,130,110));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);canvas.drawPath(p,paint);paint.setStyle(Paint.Style.FILL);baseSample+=a.length;}return;
            }
            List<WaveAudit.Window> all=stats.waveAudit.sorted();canvas.drawText("R26: separate windows, no interpolation",12,32,paint);
            if(all.isEmpty()){canvas.drawText("Waiting for historical R26",12,75,paint);return;}
            List<WaveAudit.Window> shown=all.subList(0,Math.min(6,all.size()));double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(WaveAudit.Window w:shown)if(w.usableEncoding())for(int n:w.samples){min=Math.min(min,n);max=Math.max(max,n);}
            if(!Double.isFinite(min)){canvas.drawText("No reconstructable windows",12,75,paint);return;}if(max==min)max=min+1;
            long base=shown.get(0).tick;WaveAudit.Window last=shown.get(shown.size()-1);double duration=(last.tick-base)/32768.0+25.0/last.rate;
            for(int j=0;j<shown.size();j++){
                WaveAudit.Window w=shown.get(j);double t=(w.tick-base)/32768.0;float start=(float)(12+t/duration*(getWidth()-24));
                paint.setColor(Color.LTGRAY);paint.setStrokeWidth(1);canvas.drawLine(start,45,start,getHeight()-15,paint);if(!w.usableEncoding())continue;
                Path path=new Path();for(int i=0;i<25;i++){float x=(float)(12+(t+i/(double)w.rate)/duration*(getWidth()-24));float y=(float)(getHeight()-18-(w.samples[i]-min)/(max-min)*(getHeight()-75));if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}
                paint.setColor(j%2==0?Color.rgb(28,83,180):Color.rgb(0,130,110));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            }
        }
    }
    @Override protected void onPause(){stopRaw();stopHistory();saveWaves();save();super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);stopScan();if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}archiveExecutor.shutdown();super.onDestroy();}
}
