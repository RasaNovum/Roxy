package net.rasanovum.roxy.tfc;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.*;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Render-only appearance identity. Canonical Mapper IDs and stored voxels never change. */
public final class TfcVoxyBridge {
    public static final int FIRST_VARIANT = 1 << 20;
    public static final int MAX_VARIANTS = 4096;
    private static final int MAX_CONTEXTS = 65536;
    private static final Logger LOG = LoggerFactory.getLogger("Roxy");
    private static final ThreadLocal<Variant> BAKE = new ThreadLocal<>();
    private static final ThreadLocal<Mesh> MESH = new ThreadLocal<>();
    private static final ThreadLocal<MeshStamp> MESH_STAMP = new ThreadLocal<>();
    private static int cameraX,cameraZ;
    private static long progressStarted;
    private static long[] progress=new long[7];
    private static Iterator<Map.Entry<Long,Set<Entry>>> progressScan;
    private static long progressDone;
    private static long progressEpoch=Long.MIN_VALUE;
    private static boolean forceMeshes, progressWasWorking;
    private static final Map<Long,Boolean> progressChunks=new HashMap<>();
    private static final ClassValue<Map<String,Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String,Field> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static volatile Factory active;
    private static volatile Object world;
    private static volatile TfcClimateStore climate;
    private static Adapter adapter;
    private static boolean warned;
    private static volatile long ticks;
    private static long day=Long.MIN_VALUE, month=Long.MIN_VALUE;
    private static long lastCalendarTick=Long.MIN_VALUE;
    private static long[] desiredCalendar;
    private static volatile long revision;
    private static long climateRevision;
    private static Iterator<Entry> sweep;
    private static Entry sweepNext;
    private static Iterator<Entry> dirtyEntries;
    private static Entry dirtyNext;
    private static Iterator<Entry> pruning;
    private static final AtomicBoolean dirtyOverflow=new AtomicBoolean();
    private static final AtomicBoolean climateArrived=new AtomicBoolean();
    private static final Set<Long> dirtyChunks=ConcurrentHashMap.newKeySet();
    private static final TfcRefreshScheduler REFRESH = new TfcRefreshScheduler();

    private TfcVoxyBridge() {}

    private static boolean installed() {
        var mods=LoadingModList.get();
        return mods!=null && mods.getModFileById("tfc")!=null;
    }

    private static Adapter adapter(ClassLoader loader) throws ReflectiveOperationException {
        if(adapter==null) adapter=new Adapter(Class.forName("net.rasanovum.roxyhost.tfc.RoxyTfcAdapter",true,loader));
        return adapter;
    }

    public static int[] initializeFactory(Object factory,int[] mappings) {
        if(!installed())return mappings;
        try {
            Adapter api=adapter(factory.getClass().getClassLoader());
            if(!(boolean)api.available.invoke(null))return mappings;
            int[] expanded=Arrays.copyOf(mappings,FIRST_VARIANT+MAX_VARIANTS);
            Arrays.fill(expanded,FIRST_VARIANT,expanded.length,-1);
            active=new Factory(factory,expanded,api.level.invoke(null));
            REFRESH.reset();
            forceMeshes=false;progressWasWorking=false;
            progressEpoch=Long.MIN_VALUE;progressScan=null;progressChunks.clear();progressDone=0;
            progress=new long[7];progressStarted=System.nanoTime();
            sweep=null;
            sweepNext=null;dirtyEntries=null;dirtyNext=null;pruning=null;
            LOG.info("TFC seasonal LoD prototype enabled: chunk climate, daily updates, {} appearance slots",MAX_VARIANTS);
            return expanded;
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure); return mappings; }
    }

    public static void registerBakery(Object bakery) {
        Factory factory=active;
        if(factory==null)return;
        try {
            if(field(bakery,"factory")!=factory.owner)return;
            factory.enqueue=(Lock)field(bakery,"enqueueLock");
            factory.worker=(Thread)field(bakery,"processingThread");
        } catch(ReflectiveOperationException failure) { warn(failure); }
    }

    public static Object resolveBakeState(Object mapper,int id,Object owner) {
        Factory factory=active;
        if(id>=FIRST_VARIANT) {
            if(factory==null || factory.owner!=owner || id-FIRST_VARIANT>=factory.variants.size())
                throw new IllegalStateException("Unowned TFC bake request");
            return factory.variants.get(id-FIRST_VARIANT).state;
        }
        try { return mapper.getClass().getMethod("getBlockStateFromBlockId",int.class).invoke(mapper,id); }
        catch(ReflectiveOperationException failure) { throw new IllegalStateException("Unable to resolve Voxy block state",failure); }
    }

    public static void beginBake(Object owner,int id) {
        BAKE.remove();
        Factory factory=active;
        if(factory!=null && factory.owner==owner && id>=FIRST_VARIANT && id-FIRST_VARIANT<factory.variants.size())
            BAKE.set(factory.variants.get(id-FIRST_VARIANT));
    }
    public static void endBake() { BAKE.remove(); }
    public static boolean isBakingVariant() { return BAKE.get()!=null; }
    public static Object bakedModel(Object original) { Variant variant=BAKE.get(); return variant==null?original:variant.model; }
    public static int tintMetadata(int layerMetadata) {
        Variant variant=BAKE.get();
        return variant==null?layerMetadata:layerMetadata | 8 | (variant.colour & 0xffffff)<<4;
    }

    public static void uploadsStarted(Object owner) {
        Factory factory=active;
        if(factory!=null && factory.owner==owner)factory.uploading.clear();
    }
    public static void modelUploading(Object owner,Object uploader) {
        Factory factory=active;
        if(factory==null || factory.owner!=owner || !uploader.getClass().getSimpleName().equals("ModelBakeResultUpload"))return;
        try { int id=(int)field(uploader,"modelId"); if(id>=0)factory.uploading.add(id); }
        catch(ReflectiveOperationException failure) { warn(failure); }
    }
    public static void uploadsCommitted(Object owner) {
        Factory factory=active;
        if(factory!=null && factory.owner==owner) {
            factory.uploaded.addAll(factory.uploading);
            factory.uploading.clear();
        }
    }

    public static void beginMesh(Object renderFactory,Object section) {
        MESH.remove();
        MESH_STAMP.remove();
        Factory factory=active;
        if(factory==null)return;
        try {
            if(field(renderFactory,"modelMan")!=factory.owner)return;
            long key=(long)field(section,"key");
            MESH_STAMP.set(new MeshStamp(new java.lang.ref.WeakReference<>(factory),key,REFRESH.begin(key)));
            MESH.set(new Mesh(factory,(int)field(section,"x"),(int)field(section,"y"),
                    (int)field(section,"z"),(int)field(section,"lvl"),key));
        } catch(ReflectiveOperationException failure) { warn(failure); }
    }
    public static void endMesh() { MESH.remove(); }
    public static void meshAccepted(long key) {
        MeshStamp stamp=MESH_STAMP.get();MESH_STAMP.remove();
        if(stamp!=null && stamp.factory.get()==active && stamp.key==key)REFRESH.accepted(key,stamp.generation);
    }

    public static int resolveModel(int nativeModel,Object renderFactory,long voxel,int index) {
        Mesh mesh=MESH.get();
        if(mesh==null || mesh.factory!=active || nativeModel<0)return nativeModel;
        return select(mesh,(int)(voxel>>>27)&0xfffff,nativeModel,index&31,index>>>10,(index>>>5)&31);
    }

    public static long readNeighbor(long[] data,int index,Object renderFactory) {
        Mesh mesh=MESH.get();
        if(mesh!=null)mesh.neighbor=index;
        return data[index];
    }

    public static int neighborResolved(Object modelFactory,int canonical,int original,Object renderFactory) {
        Mesh mesh=MESH.get();
        if(mesh==null || mesh.factory!=active)return original;
        int face=mesh.neighbor>>>10, a=mesh.neighbor&31,b=(mesh.neighbor>>>5)&31;
        return switch(face) {
            case 0 -> select(mesh,canonical,original,-1,b,a);
            case 1 -> select(mesh,canonical,original,32,b,a);
            case 2 -> select(mesh,canonical,original,a,-1,b);
            case 3 -> select(mesh,canonical,original,a,32,b);
            case 4 -> select(mesh,canonical,original,a,b,-1);
            case 5 -> select(mesh,canonical,original,a,b,32);
            default -> original;
        };
    }

    private static int select(Mesh mesh,int canonical,int original,int localX,int localY,int localZ) {
        Factory factory=mesh.factory;
        try {
            if(factory.leafKinds[canonical]==-1)return original;
            Object state=factory.states.computeIfAbsent(canonical,id -> {
                try { return factory.stateLookup.invoke(factory.mapper,id); }
                catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            boolean leaf=factory.leafKinds[canonical]==1;
            if(factory.leafKinds[canonical]==0) {
                leaf=(boolean)adapter.isLeaf.invoke(null,state);
                factory.leafKinds[canonical]=(byte)(leaf?1:-1);
            }
            if(!leaf)return original;
            if(factory!=active)return original;
            Object normalized=factory.normalized.computeIfAbsent(canonical,id -> {
                try { return adapter.normalize.invoke(null,factory.states.get(id)); }
                catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            int representative=factory.representatives.computeIfAbsent(normalized,ignored -> canonical);
            REFRESH.track(mesh.key);
            int x=coordinate(mesh.x,localX,mesh.lod), y=coordinate(mesh.y,localY,mesh.lod), z=coordinate(mesh.z,localZ,mesh.lod);
            if(!withinRadius(x>>4,z>>4))return original;
            long chunk=TfcClimateStore.key(x>>4,z>>4);
            boolean known=mesh.known.computeIfAbsent(chunk,ignored -> climate!=null && climate.contains(x>>4,z>>4));
            if(!known && factory.unknown.size()<MAX_CONTEXTS && withinRadius(x>>4,z>>4)) {
                Set<Long> sections=factory.unknown.computeIfAbsent(chunk,ignored->ConcurrentHashMap.newKeySet());
                if(sections.size()<64)sections.add(mesh.key);
            }
            Cell cell=known?new Cell(representative,x>>4,z>>4,y>>4):null;
            Entry entry=known?factory.contexts.get(cell):factory.fallbacks.computeIfAbsent(representative,
                    ignored -> new Entry(new Cell(representative,0,0,0),normalized,true));
            if(entry==null) {
                synchronized(factory.contexts) {
                    entry=factory.contexts.get(cell);
                    if(entry==null) {
                        if(factory.contexts.size()>=MAX_CONTEXTS)return original;
                        entry=new Entry(cell,normalized);
                        factory.contexts.put(cell,entry);
                        factory.byChunk.computeIfAbsent(TfcClimateStore.key(cell.x,cell.z),key -> ConcurrentHashMap.newKeySet()).add(entry);
                    }
                }
            }
            entry.touched=ticks;
            if(entry.sections.size()<65536)entry.sections.add(mesh.key);
            if(factory!=active || entry.retired)return original;
            if(outdated(entry))enqueue(factory,entry);
            return entry.model>=0?entry.model:original;
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure); return original; }
    }

    public static int coordinate(int section,int local,int lod) {
        return Math.toIntExact((((long)section<<5)+local)*(1L<<lod)+(lod==0?0:1L<<(lod-1)));
    }

    private static boolean enqueue(Factory factory,Entry entry) {
        if(factory!=active || entry.retired)return true;
        if(!entry.fallback && !withinRadius(entry.cell.x,entry.cell.z))return true;
        if(!entry.queued.compareAndSet(false,true))return true;
        if((entry.fallback?factory.fallbackSlots:factory.queueSlots).tryAcquire()) {
            (entry.fallback?factory.fallbackQueue:factory.queue).add(entry);
            return true;
        }
        entry.queued.set(false);
        factory.queueOverflow.set(true);
        return false;
    }

    private static boolean outdated(Entry entry) {
        return (entry.fallback?entry.revision<0:entry.revision!=revision) || entry.evaluatedDirty!=entry.dirty;
    }

    public static synchronized void capture(Object level,int x,int z,float[] snapshot) {
        if(level==null)return;
        ensureWorld(level);
        captureSnapshot(x,z,snapshot);
    }

    public static synchronized void captureImported(Object engine,int x,int z,float[] snapshot) {
        Factory factory=active;
        if(engine==null || factory==null || climate==null || world!=factory.level)return;
        try {
            if(engine.getClass().getMethod("getMapper").invoke(engine)!=factory.mapper)return;
            captureSnapshot(x,z,snapshot);
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure); }
    }

    private static void captureSnapshot(int x,int z,float[] snapshot) {
        boolean newlyKnown=!climate.contains(x,z);
        if(climate.put(x,z,snapshot)) {
            if(newlyKnown)climateArrived.set(true);
            if(dirtyChunks.size()<4096)dirtyChunks.add(TfcClimateStore.key(x,z));
            else dirtyOverflow.set(true);
        }
    }

    private static synchronized void ensureWorld(Object level) {
        if(world==level)return;
        if(climate!=null)climate.close();
        world=level;
        climate=level==null?null:new TfcClimateStore();
        if(active!=null && active.level!=level)active=null;
        day=Long.MIN_VALUE;month=Long.MIN_VALUE;revision++;climateRevision=0;sweep=null;
        lastCalendarTick=Long.MIN_VALUE;desiredCalendar=null;
        sweepNext=null;dirtyEntries=null;dirtyNext=null;pruning=null;dirtyOverflow.set(false);
        dirtyChunks.clear();REFRESH.reset();
        forceMeshes=false;progressWasWorking=false;
        progressScan=null;progress=new long[7];progressChunks.clear();progressEpoch=Long.MIN_VALUE;progressStarted=System.nanoTime();
        climateArrived.set(false);
    }

    public static void tick(Object level) {
        if(!installed())return;
        ensureWorld(level);
        Factory factory=active;
        if(level==null || factory==null || factory.level!=level)return;
        ticks++;
        try {
            Object renderer=Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem",false,level.getClass().getClassLoader())
                    .getMethod("getNullable").invoke(null);
            if(renderer==null)return;
            adapter.tickClimate.invoke(null);
            int[] camera=(int[])adapter.camera.invoke(null);
            cameraX=camera[0];cameraZ=camera[1];REFRESH.camera(cameraX,cameraZ);
            if(factory.storagePath==null) {
                Object instance=Class.forName("me.cortex.voxy.commonImpl.VoxyCommon",false,level.getClass().getClassLoader())
                        .getMethod("getInstance").invoke(null);
                factory.storagePath=TfcStorageIdentity.resolve(instance,level);
                climate.attach(factory.storagePath);
            }
            long currentDay=(long)adapter.day.invoke(null);
            long[] calendar=(long[])adapter.calendar.invoke(null);
            if(currentDay==Long.MIN_VALUE || calendar==null || calendar.length<2)return;
            long currentMonth=calendar[1];
            if(day!=currentDay || month!=currentMonth || calendar[0]<lastCalendarTick) {
                day=currentDay;month=currentMonth;revision++;
                desiredCalendar=calendar.clone();
                progressStarted=System.nanoTime();progressScan=null;
                sweep=factory.contexts.values().iterator();
                sweepNext=null;
            }
            lastCalendarTick=calendar[0];
            if(sweep==null && factory.queueSlots.availablePermits()>64 && factory.queueOverflow.getAndSet(false)) {
                sweep=factory.contexts.values().iterator();
                sweepNext=null;
            }
            if(climateRevision!=climate.loadRevision() || sweep==null && dirtyOverflow.getAndSet(false)) {
                climateRevision=climate.loadRevision();revision++;
                sweep=factory.contexts.values().iterator();
                sweepNext=null;
                climateArrived.set(true);
            }
            climateArrived.set(false);
            if(factory.unknownScan==null)factory.unknownScan=factory.unknown.entrySet().iterator();
            if(factory.unknownScan!=null)for(int i=0;i<128;i++) {
                if(!factory.unknownScan.hasNext()){factory.unknownScan=null;break;}
                var unknown=factory.unknownScan.next();long chunk=unknown.getKey();
                if(!withinRadius((int)(chunk>>32),(int)chunk)) {factory.unknown.remove(chunk,unknown.getValue());continue;}
                if(climate.contains((int)(chunk>>32),(int)chunk)) {
                    for(long key:unknown.getValue())REFRESH.request(key);
                    factory.unknown.remove(chunk,unknown.getValue());
                } else adapter.requestClimate.invoke(null,level,(int)(chunk>>32),(int)chunk);
            }
            if(factory.fallbackSweep==null)factory.fallbackSweep=factory.fallbacks.values().iterator();
            for(int i=0;i<64 && factory.fallbackSweep.hasNext();i++) {
                Entry entry=factory.fallbackSweep.next();
                if(outdated(entry))enqueue(factory,entry);
            }
            if(!factory.fallbackSweep.hasNext())factory.fallbackSweep=null;
            for(int count=0;count<64;count++) {
                if(dirtyNext==null) {
                    if(dirtyEntries==null || !dirtyEntries.hasNext()) {
                        Iterator<Long> chunks=dirtyChunks.iterator();
                        if(!chunks.hasNext()){dirtyEntries=null;break;}
                        long chunk=chunks.next();chunks.remove();
                        var entries=factory.byChunk.get(chunk);
                        dirtyEntries=entries==null?Collections.emptyIterator():entries.iterator();
                        if(!dirtyEntries.hasNext())continue;
                    }
                    dirtyNext=dirtyEntries.next();
                    dirtyNext.dirty++;
                }
                if(!enqueue(factory,dirtyNext))break;
                dirtyNext=null;
            }
            if(sweep!=null) {
                for(int i=0;i<64;i++) {
                    if(sweepNext==null) {
                        if(!sweep.hasNext()){sweep=null;break;}
                        sweepNext=sweep.next();
                    }
                    if(outdated(sweepNext) && !enqueue(factory,sweepNext))break;
                    sweepNext=null;
                }
            }
            long deadline=System.nanoTime()+750_000;
            for(int i=0;i<64 && System.nanoTime()<deadline;i++) {
                Entry entry=i==0 && ticks%8==0?factory.fallbackQueue.poll():null;
                if(entry==null)entry=factory.queue.poll();
                if(entry==null)entry=factory.fallbackQueue.poll();
                if(entry==null)break;
                (entry.fallback?factory.fallbackSlots:factory.queueSlots).release();
                entry.queued.set(false);
                if(entry.retired)continue;
                evaluate(factory,entry);
            }
            int waiting=factory.waiting.size();
            for(int i=0;i<Math.min(64,waiting);i++) {
                Entry entry=factory.waiting.removeFirst();
                if(!commitReady(factory,entry))factory.waiting.addLast(entry);
            }
            drainForcedMeshes(factory);
            REFRESH.tick(renderer);
            updateProgress(factory);
            prune(factory);
            if(ticks%100==0)climate.flushAsync();
            if(ticks%600==0)LOG.debug("TFC LoDs: climate={}, contexts={}, fallbacks={}, variants={}, evaluating={}, waiting={}, remesh={}",
                    climate.size(),factory.contexts.size(),factory.fallbacks.size(),factory.variants.size(),factory.queue.size()+factory.fallbackQueue.size(),factory.waiting.size(),REFRESH.pendingCount());
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure); }
    }

    private static boolean commitReady(Factory factory,Entry entry) {
        Variant variant=entry.pending;
        if(variant==null || entry.retired || factory!=active){entry.waiting=false;return true;}
        if(outdated(entry)) {
            enqueue(factory,entry);
            return false;
        }
        int model=factory.mappings[variant.request];
        if(model<0 || !factory.uploaded.contains(model))return false;
        entry.model=model;entry.pending=null;entry.waiting=false;
        refresh(entry);
        return true;
    }

    private static void drainForcedMeshes(Factory factory) {
        if(forceMeshes && sweep==null && dirtyNext==null && dirtyChunks.isEmpty()
                && factory.queue.isEmpty() && factory.fallbackQueue.isEmpty() && factory.waiting.isEmpty()) {
            REFRESH.requestResidentSweep();
            forceMeshes=false;
        }
    }

    private static void refresh(Entry entry) {
        for(long section:entry.sections)REFRESH.request(section);
    }

    private static boolean withinRadius(int x,int z) {
        long dx=(long)x-cameraX,dz=(long)z-cameraZ;return dx*dx+dz*dz<=512L*512;
    }
    private static void updateProgress(Factory factory) {
        if(progressEpoch!=revision){progressEpoch=revision;progressChunks.clear();progressDone=0;progressScan=null;}
        if(progressScan==null)progressScan=factory.byChunk.entrySet().iterator();
        for(int i=0;i<128 && progressScan.hasNext();i++) {
            var chunk=progressScan.next();long key=chunk.getKey();
            if(!withinRadius((int)(key>>32),(int)key)){removeProgressChunk(key);continue;}
            boolean complete=true,present=false;
            for(Entry entry:chunk.getValue())if(!entry.retired){
                present=true;
                if(forceMeshes||outdated(entry)||entry.pending!=null||entry.sections.stream().anyMatch(section->!REFRESH.complete(section)))complete=false;
            }
            if(present){Boolean old=progressChunks.put(key,complete);if(Boolean.TRUE.equals(old))progressDone--;if(complete)progressDone++;}
            else removeProgressChunk(key);
        }
        if(!progressScan.hasNext())progressScan=null;
        int pending=REFRESH.pendingCount();
        boolean working=forceMeshes||sweep!=null||dirtyNext!=null||!dirtyChunks.isEmpty()||!factory.queue.isEmpty()||!factory.fallbackQueue.isEmpty()||!factory.waiting.isEmpty()||pending>0||progressDone<progressChunks.size();
        progress=new long[]{revision,progressDone,progressChunks.size(),factory.unknown.size(),working?1:0,(System.nanoTime()-progressStarted)/1_000_000,pending};
        if(progressWasWorking&&!working)LOG.debug("TFC LoD refresh queues drained: revision={}, sampled chunks={}/{}, unavailable climate={}, submitted={}, accepted={}, changed={}, unchanged={}",
                revision,progressDone,progressChunks.size(),factory.unknown.size(),REFRESH.submittedCount(),REFRESH.acceptedCount(),factory.changed,factory.unchanged);
        progressWasWorking=working;
    }
    private static void removeProgressChunk(long key){if(Boolean.TRUE.equals(progressChunks.remove(key)))progressDone--;}
    public static long[] refreshProgress(){return progress.clone();}

    public static String status() {
        Factory factory=active;
        TfcClimateStore store=climate;
        if(factory==null || world!=factory.level)return "TFC seasonal LoDs: inactive";
        return "TFC seasonal LoDs: day="+day+", revision="+revision
                +", climate="+(store==null?0:store.size())+", contexts="+factory.contexts.size()
                +", fallbacks="+factory.fallbacks.size()
                +", variants="+factory.variants.size()+"/"+MAX_VARIANTS
                +", evaluating="+(factory.queue.size()+factory.fallbackQueue.size())+", waiting="+factory.waiting.size()
                +", remesh="+REFRESH.pendingCount()+", submitted="+REFRESH.submittedCount()+", accepted="+REFRESH.acceptedCount()
                +", changed="+factory.changed+", unchanged="+factory.unchanged;
    }

    public static boolean forceRefresh() {
        Factory factory=active;
        if(factory==null || world!=factory.level || adapter==null)return false;
        try {
            long currentDay=(long)adapter.day.invoke(null);
            long[] calendar=(long[])adapter.calendar.invoke(null);
            if(currentDay==Long.MIN_VALUE || calendar==null || calendar.length<2)return false;
            day=currentDay;month=calendar[1];lastCalendarTick=calendar[0];
            desiredCalendar=calendar.clone();revision++;
            forceMeshes=true;
            progressStarted=System.nanoTime();progressScan=null;
            sweep=factory.contexts.values().iterator();sweepNext=null;
            return true;
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure);return false; }
    }

    private static void evaluate(Factory factory,Entry entry) throws ReflectiveOperationException {
        if(!outdated(entry) || entry.retired || factory!=active || desiredCalendar==null)return;
        float[] snapshot=entry.fallback?null:climate.get(entry.cell.x,entry.cell.z);
        Object[] appearance=(Object[])adapter.evaluate.invoke(null,entry.state,entry.cell.x*16+8,entry.cell.y*16+8,entry.cell.z*16+8,snapshot,desiredCalendar);
        if(appearance==null){entry.revision=revision;entry.evaluatedDirty=entry.dirty;entry.pending=null;return;}
        int colour=quantize((int)appearance[1]);
        Appearance key=new Appearance(entry.state,appearance[0],colour);
        Variant variant=factory.palette.get(key);
        if(variant==null) {
            if(factory.variants.size()>=MAX_VARIANTS) {
                if(!factory.full){factory.full=true;LOG.warn("TFC LoD appearance budget reached; keeping existing appearances until renderer reload");}
                entry.revision=revision;entry.evaluatedDirty=entry.dirty;entry.pending=null;return;
            }
            if(factory.enqueue==null || !factory.enqueue.tryLock()){enqueue(factory,entry);return;}
            try {
                variant=new Variant(entry.state,appearance[0],colour,FIRST_VARIANT+factory.variants.size());
                factory.variants.add(variant);
                factory.palette.put(key,variant);
                factory.add.invoke(factory.owner,variant.request);
                LockSupport.unpark(factory.worker);
            } finally { factory.enqueue.unlock(); }
        }
        entry.revision=revision;
        entry.evaluatedDirty=entry.dirty;
        int model=factory.mappings[variant.request];
        if(model>=0 && entry.model==model)factory.unchanged++;else factory.changed++;
        if(model>=0 && factory.uploaded.contains(model)) {
            if(entry.model!=model) { entry.model=model;refresh(entry); }
            entry.pending=null;
        } else {
            entry.pending=variant;
            if(!entry.waiting){entry.waiting=true;factory.waiting.addLast(entry);}
        }
    }

    private static void prune(Factory factory) {
        if(pruning==null)pruning=factory.contexts.values().iterator();
        int budget=factory.contexts.size()>=MAX_CONTEXTS-256?256:32;
        for(int i=0;i<budget && pruning.hasNext();i++) {
            Entry entry=pruning.next();
            if(ticks-entry.touched<200)continue;
            if(entry.sections.stream().anyMatch(REFRESH::isWatched))continue;
            synchronized(factory.contexts) {
                if(ticks-entry.touched<200)continue;
                entry.retired=true;
                factory.contexts.remove(entry.cell,entry);
                long chunk=TfcClimateStore.key(entry.cell.x,entry.cell.z);
                var entries=factory.byChunk.get(chunk);
                if(entries!=null) {
                    entries.remove(entry);
                    if(entries.isEmpty()){factory.byChunk.remove(chunk,entries);removeProgressChunk(chunk);}
                }
            }
        }
        if(!pruning.hasNext())pruning=null;
    }

    public static int quantize(int rgb) {
        int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;
        return Math.min(255,(r+4)/8*8)<<16 | Math.min(255,(g+4)/8*8)<<8 | Math.min(255,(b+4)/8*8);
    }

    private static Object field(Object object,String name) throws ReflectiveOperationException {
        Map<String,Field> fields=FIELDS.get(object.getClass());
        Field field=fields.get(name);
        if(field==null) { field=object.getClass().getDeclaredField(name);field.setAccessible(true);fields.put(name,field); }
        return field.get(object);
    }
    private static void warn(Throwable failure) {
        if(!warned){warned=true;LOG.warn("TFC seasonal LoD prototype could not complete an update; preserving existing geometry",failure);}
    }

    private record Adapter(Method available,Method isLeaf,Method normalize,Method level,Method evaluate,Method day,Method calendar,Method camera,Method requestClimate,Method tickClimate) {
        Adapter(Class<?> type) throws ReflectiveOperationException {
            this(type.getMethod("available"),type.getMethod("isLeaf",Object.class),type.getMethod("normalize",Object.class),type.getMethod("clientLevel"),
                    type.getMethod("evaluate",Object.class,int.class,int.class,int.class,Object.class,Object.class),
                    type.getMethod("calendarDay"),type.getMethod("calendarSnapshot"),type.getMethod("cameraChunk"),
                    type.getMethod("requestClimate",Object.class,int.class,int.class),type.getMethod("tickClimate"));
        }
    }
    private record Cell(int state,int x,int z,int y) {}
    private record Appearance(Object state,Object model,int colour) {}
    private record Variant(Object state,Object model,int colour,int request) {}
    private record MeshStamp(java.lang.ref.WeakReference<Factory> factory,long key,long generation) {}
    private static final class Entry {
        final Cell cell; final Object state;
        final boolean fallback;
        final Set<Long> sections=ConcurrentHashMap.newKeySet();
        final AtomicBoolean queued=new AtomicBoolean();
        volatile long revision=-1;
        volatile long dirty;
        volatile long evaluatedDirty;
        volatile long touched;
        volatile boolean retired;
        volatile int model=-1;
        Variant pending;boolean waiting;
        Entry(Cell cell,Object state){this(cell,state,false);}
        Entry(Cell cell,Object state,boolean fallback){this.cell=cell;this.state=state;this.fallback=fallback;}
    }
    private static final class Mesh {
        final Factory factory;final int x,y,z,lod;final long key;int neighbor;
        final Map<Long,Boolean> known=new HashMap<>();
        Mesh(Factory factory,int x,int y,int z,int lod,long key){this.factory=factory;this.x=x;this.y=y;this.z=z;this.lod=lod;this.key=key;}
    }
    private static final class Factory {
        final Object owner,mapper,level;final int[] mappings;final Method stateLookup,add;
        final Map<Integer,Object> states=new ConcurrentHashMap<>();
        final Map<Integer,Object> normalized=new ConcurrentHashMap<>();
        final Map<Object,Integer> representatives=new ConcurrentHashMap<>();
        final ConcurrentMap<Integer,Entry> fallbacks=new ConcurrentHashMap<>();
        Iterator<Entry> fallbackSweep;
        final byte[] leafKinds=new byte[FIRST_VARIANT];
        final ConcurrentMap<Cell,Entry> contexts=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,Set<Entry>> byChunk=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,Set<Long>> unknown=new ConcurrentHashMap<>();
        Iterator<Map.Entry<Long,Set<Long>>> unknownScan;
        final Queue<Entry> queue=new ConcurrentLinkedQueue<>();
        final Queue<Entry> fallbackQueue=new ConcurrentLinkedQueue<>();
        final Semaphore queueSlots=new Semaphore(4096);
        final Semaphore fallbackSlots=new Semaphore(256);
        final AtomicBoolean queueOverflow=new AtomicBoolean();
        final Map<Appearance,Variant> palette=new HashMap<>();
        final List<Variant> variants=new CopyOnWriteArrayList<>();
        final ArrayDeque<Entry> waiting=new ArrayDeque<>();
        final Set<Integer> uploaded=ConcurrentHashMap.newKeySet();
        final Set<Integer> uploading=new HashSet<>();
        Lock enqueue;Thread worker;java.nio.file.Path storagePath;boolean full;
        long changed,unchanged;
        Factory(Object owner,int[] mappings,Object level) throws ReflectiveOperationException {
            this.owner=owner;this.mappings=mappings;this.level=level;
            mapper=field(owner,"mapper");
            stateLookup=mapper.getClass().getMethod("getBlockStateFromBlockId",int.class);
            add=owner.getClass().getMethod("addEntry",int.class);
        }
    }
}
