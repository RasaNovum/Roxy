package net.rasanovum.roxy.tfc;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TfcVoxyBridge {
    public static final int FIRST_VARIANT = 1 << 20;
    public static final int MAX_VARIANTS = 4096;
    private static final int MAX_VOXY_MODEL_IDS = 256*256;
    private static final int MAX_CONTEXTS = 65536;
    private static final int MAX_FALLBACK_CONTEXTS = 4096;
    private static final int MAX_COLOR_VARIANTS_PER_FAMILY = 8;
    private static final int GEOMETRY_FAMILY_RESERVE = 1024;
    private static final int MAX_RECOLOR_SECTIONS = 4096;
    private static final int MAX_DEFERRED_CONTEXTS = 4096;
    private static final int MAX_DEFERRED_SECTIONS = 64;
    private static final long NEAR_RADIUS_SQUARED = 32L * 32;
    private static final long EVICTION_HYSTERESIS_SQUARED = 8L * 8;
    private static final Logger LOG = LoggerFactory.getLogger("Roxy");
    private static final ThreadLocal<Variant> BAKE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> BAKE_TINT = new ThreadLocal<>();
    private static final ThreadLocal<Mesh> MESH = new ThreadLocal<>();
    private static final ThreadLocal<MeshStamp> MESH_STAMP = new ThreadLocal<>();
    private static int cameraX,cameraZ;
    private static long progressStarted;
    private static long[] progress=new long[8];
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
    private static boolean compatWasEnabled;
    private static boolean configSeen;
    private static boolean rendererReloadRequested;
    private static volatile boolean compatFastEnabled;
    private static volatile long ticks;
    private static long day=Long.MIN_VALUE, month=Long.MIN_VALUE;
    private static long lastCalendarTick=Long.MIN_VALUE;
    private static long[] desiredCalendar;
    private static volatile long revision;
    private static long deferredSweepRevision=Long.MIN_VALUE;
    private static long climateRevision;
    private static Iterator<Entry> sweep;
    private static List<Entry> sweepOrder=List.of();
    private static volatile boolean sweepDirty;
    private static long lastSweepRebuildTick=Long.MIN_VALUE;
    private static Entry sweepNext;
    private static Iterator<Entry> dirtyEntries;
    private static Entry dirtyNext;
    private static Iterator<Entry> pruning;
    private static final AtomicBoolean dirtyOverflow=new AtomicBoolean();
    private static final AtomicBoolean climateArrived=new AtomicBoolean();
    private static final Set<Long> dirtyChunks=ConcurrentHashMap.newKeySet();
    private static final Set<Long> removedProgressSet=ConcurrentHashMap.newKeySet();
    private static final Queue<Long> removedProgressChunks=new ConcurrentLinkedQueue<>();
    private static final TfcRefreshScheduler REFRESH = new TfcRefreshScheduler();

    private TfcVoxyBridge() {}

    private static Adapter adapter(ClassLoader loader) throws ReflectiveOperationException {
        if(adapter==null) adapter=new Adapter(Class.forName("net.rasanovum.roxyhost.tfc.RoxyTfcAdapter",true,loader));
        return adapter;
    }

    public static int[] initializeFactory(Object factory,int[] mappings) {
        boolean enabled=TfcCompatConfig.enabled();
        compatFastEnabled=enabled;
        if(!enabled)return mappings;
        try {
            Adapter api=adapter(factory.getClass().getClassLoader());
            if(!(boolean)api.available.invoke(null,factory.getClass().getClassLoader()))return mappings;
            int[] expanded=Arrays.copyOf(mappings,FIRST_VARIANT+MAX_VARIANTS);
            Arrays.fill(expanded,FIRST_VARIANT,expanded.length,-1);
            active=new Factory(factory,expanded,api.level.invoke(null));
            compatWasEnabled=true;
            REFRESH.reset();
            forceMeshes=false;progressWasWorking=false;
            progressEpoch=Long.MIN_VALUE;progressScan=null;progressChunks.clear();progressDone=0;
            progress=new long[8];progressStarted=System.nanoTime();
            sweep=null;sweepOrder=List.of();sweepDirty=false;lastSweepRebuildTick=Long.MIN_VALUE;clearRemovedProgress();
            sweepNext=null;dirtyEntries=null;dirtyNext=null;pruning=null;
            LOG.info("TFC seasonal LoDs enabled: chunk climate, daily updates, {} appearance slots",MAX_VARIANTS);
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

    public static boolean isVariantBake(Object owner,int id) {
        Factory factory=active;
        return factory!=null && factory.owner==owner && id>=FIRST_VARIANT
                && id-FIRST_VARIANT<factory.variants.size();
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
        BAKE_TINT.remove();
        Factory factory=active;
        if(factory!=null && factory.owner==owner && id>=FIRST_VARIANT && id-FIRST_VARIANT<factory.variants.size())
            BAKE.set(factory.variants.get(id-FIRST_VARIANT));
    }
    public static void endBake() { BAKE.remove();BAKE_TINT.remove(); }
    public static boolean isBakingVariant() { return BAKE.get()!=null; }
    public static int modelKey(int tint) {
        Variant variant=BAKE.get();
        if(variant==null)return tint;
        BAKE_TINT.set(tint);
        return modelMarker(variant.request);
    }
    public static int modelTint(int key) {
        Variant variant=BAKE.get();
        Integer tint=BAKE_TINT.get();
        return variant!=null && tint!=null && key==modelMarker(variant.request)?tint:key;
    }
    private static int modelMarker(int request){return Integer.MIN_VALUE | (request-FIRST_VARIANT);}
    public static int variantBakeReady(Object owner,int request,Object uploader) {
        if(request<FIRST_VARIANT)return 0;
        Factory factory=active;
        if(uploader==null) {
            if(factory!=null && factory.owner==owner) {
                RecolorPlan plan=factory.pendingByRequest.get(request);
                if(plan!=null)cancelRecolor(factory,plan);
                else if(request-FIRST_VARIANT<factory.variants.size())
                    fallbackFailedVariant(factory,factory.variants.get(request-FIRST_VARIANT));
            }
            return 0;
        }
        if(factory==null || factory.owner!=owner || request-FIRST_VARIANT>=factory.variants.size())return 0;
        Variant baked=BAKE.get();
        int candidate=-1;
        Object candidateEntryKey=null;
        if(baked==null || baked.request!=request) {
            try {
                candidate=(int)field(uploader,"modelId");
                return discardUpload(factory,factory.variants.get(request-FIRST_VARIANT),uploader,candidate,
                        factory.pendingByRequest.get(request));
            } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) { warn(failure);return 1; }
        }
        try {
            candidate=(int)field(uploader,"modelId");
            if(candidate<0 || candidate>=MAX_VOXY_MODEL_IDS)return discardUpload(factory,baked,uploader,candidate,null);
            RecolorPlan plan=factory.pendingByRequest.get(request);
            if(plan==null) {
                if(factory.variants.get(request-FIRST_VARIANT)!=baked || baked.revision!=revision)return discardUpload(factory,baked,uploader,candidate,null);
                List<Map.Entry<?,?>> entries=modelEntries(factory);
                Map.Entry<?,?> found=modelEntryForRequest(entries,request);
                if(found==null || ((Number)found.getValue()).intValue()!=candidate
                        || factory.modelIdOwners.putIfAbsent(candidate,baked)!=null)
                    return discardUpload(factory,baked,uploader,candidate,null);
                baked.modelBytes=TfcVoxyUploadPatch.modelBytes(uploader);
                baked.textureAlpha=TfcVoxyUploadPatch.textureAlphaBytes(uploader);
                if(baked.modelBytes==null || baked.textureAlpha==null)
                    return discardUpload(factory,baked,uploader,candidate,null);
                baked.modelId=candidate;
                baked.modelEntryKey=found.getKey();
                return 0;
            }
            if(plan.replacement!=baked || plan.replacement.revision!=revision || factory.variants.get(request-FIRST_VARIANT)!=baked)
                return discardUpload(factory,baked,uploader,candidate,plan);
            if((int)field(uploader,"biomeUploadIndex")!=-1 || field(uploader,"biomeUpload")!=null
                    || plan.previous.modelId<0 || plan.previous.modelId>=MAX_VOXY_MODEL_IDS
                    || plan.previous.modelBytes==null || plan.previous.textureAlpha==null
                    || TfcVoxyUploadPatch.modelUsesBiomeColours(uploader)
                    || !TfcVoxyUploadPatch.sameModelBytes(uploader,plan.previous.modelBytes)
                    || !TfcVoxyUploadPatch.sameTextureAlpha(uploader,plan.previous.textureAlpha))
                return discardUpload(factory,baked,uploader,candidate,plan);
            List<Map.Entry<?,?>> entries=modelEntries(factory);
            Map.Entry<?,?> newEntry=modelEntryForRequest(entries,request,candidate,plan.previous.modelEntryKey);
            candidateEntryKey=newEntry==null?null:newEntry.getKey();
            Map.Entry<?,?> oldEntry=entries.stream().filter(entry->entry.getKey()==plan.previous.modelEntryKey).findFirst().orElse(null);
            if(newEntry==null || ((Number)newEntry.getValue()).intValue()!=candidate || oldEntry==null
                    || ((Number)oldEntry.getValue()).intValue()!=plan.previous.modelId
                    || newEntry.getKey()==oldEntry.getKey()
                    || factory.modelIdOwners.get(plan.previous.modelId)!=plan.previous)
                return discardUpload(factory,baked,uploader,candidate,plan);
            @SuppressWarnings("unchecked") Map<Object,Integer> mutable=(Map<Object,Integer>)field(owner,"modelTexture2id");
            mutable.remove(oldEntry.getKey());
            mutable.put(newEntry.getKey(),plan.previous.modelId);
            fieldSet(uploader,"modelId",plan.previous.modelId);
            factory.mappings[request]=plan.previous.modelId;
            baked.modelId=plan.previous.modelId;
            baked.modelBytes=plan.previous.modelBytes;
            baked.textureAlpha=TfcVoxyUploadPatch.textureAlphaBytes(uploader);
            baked.modelEntryKey=newEntry.getKey();
            factory.modelIdOwners.put(plan.previous.modelId,baked);
            plan.uploading=true;
            return 0;
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return discardUpload(factory,baked,uploader,candidate,factory.pendingByRequest.get(request),candidateEntryKey);
        }
    }
    private static int discardUpload(Factory factory,Variant baked,Object uploader,int candidate,RecolorPlan plan) {
        return discardUpload(factory,baked,uploader,candidate,plan,null);
    }
    private static int discardUpload(Factory factory,Variant baked,Object uploader,int candidate,RecolorPlan plan,Object candidateEntryKey) {
        try {
            @SuppressWarnings("unchecked") Map<Object,Integer> raw=(Map<Object,Integer>)field(factory.owner,"modelTexture2id");
            int request=baked==null?0:baked.request;
            Map.Entry<?,?> generated=null;
            List<Map.Entry<?,?>> entries=modelEntries(factory);
            if(plan!=null)generated=candidateEntryKey==null
                    ?modelEntryForRequest(entries,request,plan.previous.modelEntryKey)
                    :entries.stream().filter(entry->entry.getKey()==candidateEntryKey
                            && entry.getKey()!=plan.previous.modelEntryKey).findFirst().orElse(null);
            else if(candidate>=0)generated=modelEntryForRequest(entries,request,candidate,null);
            if(generated!=null)raw.remove(generated.getKey());
            if(plan!=null) {
                if(plan.replacement.revision==revision)plan.previous.lastRecoloredRevision=revision;
                raw.put(plan.previous.modelEntryKey,plan.previous.modelId);
                factory.mappings[plan.previous.request]=plan.previous.modelId;
                factory.variants.set(plan.previous.request-FIRST_VARIANT,plan.previous);
                factory.modelIdOwners.put(plan.previous.modelId,plan.previous);
                factory.uploaded.add(plan.previous.modelId);
                factory.uploadedGenerations.put(plan.previous.request,plan.previous.generation);
                factory.pendingByRequest.remove(plan.previous.request,plan);
                factory.pendingByFamily.remove(plan.previous.family,plan);
                factory.pendingPalette.remove(plan.replacement.appearance(),plan);
                restorePlanEntries(factory,plan);
            } else if(baked!=null) {
                factory.modelIdOwners.remove(candidate,baked);
                fallbackFailedVariant(factory,baked);
            }
            return 1;
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure);return 1; }
    }
    private static int rejectModelUpload(Factory factory,Variant variant,Object uploader,int modelId,RecolorPlan plan) {
        if(plan!=null)factory.recolorUploadRejects++;
        discardUpload(factory,variant,uploader,modelId,plan);
        return 2;
    }
    public static int modelUploading(Object owner,Object uploader) {
        Factory factory=active;
        if(factory==null || factory.owner!=owner || !uploader.getClass().getSimpleName().equals("ModelBakeResultUpload"))return 0;
        int id=-1;Variant variant=null;RecolorPlan plan=null;
        try {
            id=(int)field(uploader,"modelId");
            variant=factory.modelIdOwners.get(id);
            if(variant==null)return 0;
            plan=factory.pendingByRequest.get(variant.request);
            if(plan!=null && plan.replacement==variant) {
                if(variant.revision!=revision || factory.variants.get(variant.request-FIRST_VARIANT)!=variant)
                    return rejectModelUpload(factory,variant,uploader,id,plan);
                if(TfcVoxyUploadPatch.modelUsesBiomeColours(uploader)
                        || !TfcVoxyUploadPatch.sameModelBytes(uploader,plan.previous.modelBytes)
                        || !TfcVoxyUploadPatch.sameTextureAlpha(uploader,plan.previous.textureAlpha))
                    return rejectModelUpload(factory,variant,uploader,id,plan);
                TfcVoxyUploadPatch.armAtlasOnly(uploader);
                plan.uploading=true;
                factory.uploadingRecolors.add(plan);
                factory.uploading.add(id);
                return 1;
            }
            if(variant.revision!=revision)return rejectModelUpload(factory,variant,uploader,id,null);
            factory.uploading.add(id);
            return 0;
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            if(variant!=null)return rejectModelUpload(factory,variant,uploader,id,plan);
            return 2;
        }
    }
    public static Object bakedModel(Object original) { Variant variant=BAKE.get(); return variant==null?original:variant.model; }
    public static int tintMetadata(int layerMetadata) {
        return tintMetadata(layerMetadata,0);
    }
    public static int tintMetadata(int layerMetadata,int tintIndex) {
        Variant variant=BAKE.get();
        if(variant==null || tintIndex<0 || tintIndex>1)return layerMetadata;
        int colour=tintIndex==0?variant.colour0:variant.colour1;
        return colour<0?layerMetadata:layerMetadata | 8 | (colour & 0xffffff)<<4;
    }

    public static void uploadsStarted(Object owner) {
        Factory factory=active;
        if(factory!=null && factory.owner==owner){factory.uploading.clear();factory.uploadingRecolors.clear();}
    }
    public static void uploadsCommitted(Object owner) {
        Factory factory=active;
        if(factory!=null && factory.owner==owner) {
            factory.uploaded.addAll(factory.uploading);
            for(int id:factory.uploading) {
                Variant variant=factory.modelIdOwners.get(id);
                if(variant!=null)factory.uploadedGenerations.put(variant.request,variant.generation);
            }
            factory.uploading.clear();
            for(RecolorPlan plan:factory.uploadingRecolors)commitRecolor(factory,plan);
            factory.uploadingRecolors.clear();
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
    public static void meshFailed() { MESH.remove();MESH_STAMP.remove(); }
    public static void meshAccepted(long key) {
        MeshStamp stamp=MESH_STAMP.get();MESH_STAMP.remove();
        if(stamp!=null && stamp.factory.get()==active && stamp.key==key)REFRESH.accepted(key,stamp.generation);
    }

    public static int resolveModel(int nativeModel,Object renderFactory,long voxel,int index) {
        if(!compatFastEnabled)return nativeModel;
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
        if(!compatFastEnabled)return original;
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
            if(factory.appearanceKinds[canonical]==-1)return original;
            Object state=factory.states.computeIfAbsent(canonical,id -> {
                try { return factory.stateLookup.invoke(factory.mapper,id); }
                catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            byte kind=factory.appearanceKinds[canonical];
            if(kind==0) {
                boolean supported=(boolean)adapter.supports.invoke(null,state);
                if(!supported) {
                    factory.appearanceKinds[canonical]=-1;
                    return original;
                }
                boolean leaf=(boolean)adapter.isLeaf.invoke(null,state);
                kind=(byte)(leaf?1:2);
                factory.appearanceKinds[canonical]=kind;
            }
            if(factory!=active)return original;
            Object normalized=factory.normalized.computeIfAbsent(canonical,id -> {
                try {
                    Object source=factory.states.get(id);
                    return factory.appearanceKinds[id]==1?adapter.normalize.invoke(null,source):source;
                }
                catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            int representative=factory.representatives.computeIfAbsent(normalized,ignored -> canonical);
            REFRESH.track(mesh.key);
            int x=coordinate(mesh.x,localX,mesh.lod), y=coordinate(mesh.y,localY,mesh.lod), z=coordinate(mesh.z,localZ,mesh.lod);
            int chunkX=x>>4,chunkZ=z>>4;
            if(!withinRadius(chunkX,chunkZ))return original;
            int contextX=chunkX,contextZ=chunkZ;
            boolean known=mesh.known.computeIfAbsent(TfcClimateStore.key(chunkX,chunkZ),ignored -> climate!=null && climate.contains(chunkX,chunkZ));
            if(kind!=1 || !nearCell(chunkX,chunkZ)) {
                int representativeX=coarseChunk(chunkX),representativeZ=coarseChunk(chunkZ);
                long representativeKey=TfcClimateStore.key(representativeX,representativeZ);
                boolean representativeKnown=mesh.known.computeIfAbsent(representativeKey,
                        ignored -> climate!=null && climate.contains(representativeX,representativeZ));
                if(representativeKnown && withinRadius(representativeX,representativeZ)) {
                    contextX=representativeX;contextZ=representativeZ;known=true;
                } else if(withinRadius(representativeX,representativeZ)) {
                    trackUnknown(factory,representativeKey,mesh.key);
                }
            }
            if(!known)trackUnknown(factory,TfcClimateStore.key(chunkX,chunkZ),mesh.key);
            Cell cell=known?new Cell(representative,contextX,contextZ,y>>4):null;
            Entry entry;
            if(known) {
                entry=factory.contexts.get(cell);
                if(entry==null) {
                    synchronized(factory.contexts) {
                        entry=factory.contexts.get(cell);
                        if(entry==null) {
                            if(factory.contexts.size()>=MAX_CONTEXTS) {
                                deferNear(factory,cell,normalized,mesh.key);
                                return original;
                            }
                            entry=new Entry(cell,normalized);
                            registerContext(factory,entry);
                        }
                    }
                }
            } else {
                entry=factory.fallbacks.get(representative);
                if(entry==null)synchronized(factory.fallbacks) {
                    entry=factory.fallbacks.get(representative);
                    if(entry==null) {
                        if(factory.fallbacks.size()>=MAX_FALLBACK_CONTEXTS)return original;
                        entry=new Entry(new Cell(representative,0,0,0),normalized,true);
                        factory.fallbacks.put(representative,entry);
                    }
                }
            }
            if(entry.nativeModel<0)entry.nativeModel=original;
            entry.touched=ticks;
            if(entry.sections.size()<65536)entry.sections.add(mesh.key);
            if(factory!=active || entry.retired)return original;
            if(outdated(entry))enqueue(factory,entry);
            return entry.model>=0?entry.model:original;
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure); return original; }
    }

    private static int coarseChunk(int chunk) {
        return Math.floorDiv(chunk,4)*4+2;
    }

    private static void trackUnknown(Factory factory,long chunk,long section) {
        if(factory.unknown.size()>=MAX_CONTEXTS)return;
        Set<Long> sections=factory.unknown.computeIfAbsent(chunk,ignored->ConcurrentHashMap.newKeySet());
        if(sections.size()<64)sections.add(section);
    }

    private static void deferNear(Factory factory,Cell cell,Object state,long section) {
        if(!nearCell(cell.x,cell.z))return;
        synchronized(factory.deferred) {
            Entry entry=factory.deferred.get(cell);
            if(entry==null) {
                if(factory.deferred.size()>=MAX_DEFERRED_CONTEXTS-1) {
                    if(factory.deferredScanTick!=ticks) {
                        Entry farthest=null;long farthestDistance=-1;
                        for(Entry candidate:factory.deferred.values()) {
                            long distance=distanceSquared(candidate.cell.x,candidate.cell.z,cameraX,cameraZ);
                            if(distance>farthestDistance){farthestDistance=distance;farthest=candidate;}
                        }
                        factory.deferredScanTick=ticks;
                        factory.deferredFarthest=farthest;
                        factory.deferredFarthestDistance=farthestDistance;
                    }
                    long distance=distanceSquared(cell.x,cell.z,cameraX,cameraZ);
                    Entry farthest=factory.deferredFarthest;
                    if(farthest==null || distance>=factory.deferredFarthestDistance)return;
                    factory.deferred.remove(farthest.cell);
                    removeChunkIndex(factory,farthest);
                    retireEntry(farthest);
                    factory.deferredFarthest=null;
                }
                entry=new Entry(cell,state);
                entry.deferred=true;
                factory.deferred.put(cell,entry);
                addChunkIndex(factory,entry);
                if(factory.deferredScanTick==ticks && factory.deferredFarthest!=null) {
                    if(distanceSquared(cell.x,cell.z,cameraX,cameraZ)>factory.deferredFarthestDistance) {
                        factory.deferredFarthest=entry;
                        factory.deferredFarthestDistance=distanceSquared(cell.x,cell.z,cameraX,cameraZ);
                    }
                }
            }
            if(entry.sections.size()<MAX_DEFERRED_SECTIONS)entry.sections.add(section);
            else entry.deferredSectionsTruncated=true;
            if(entry.model>=0)entry.needsRefresh=true;
            entry.touched=ticks;
        }
    }

    private static void registerContext(Factory factory,Entry entry) {
        factory.contexts.put(entry.cell,entry);
        addChunkIndex(factory,entry);
        sweepDirty=true;
    }

    private static void addChunkIndex(Factory factory,Entry entry) {
        factory.byChunk.computeIfAbsent(TfcClimateStore.key(entry.cell.x,entry.cell.z),key->ConcurrentHashMap.newKeySet()).add(entry);
    }

    private static void removeChunkIndex(Factory factory,Entry entry) {
        long chunk=TfcClimateStore.key(entry.cell.x,entry.cell.z);
        var entries=factory.byChunk.get(chunk);
        if(entries!=null) {
            entries.remove(entry);
            if(entries.isEmpty()) {
                factory.byChunk.remove(chunk,entries);
                if(removedProgressSet.add(chunk))removedProgressChunks.add(chunk);
            }
        }
    }

    private static void resetAdmissionScan(Factory factory) {
        factory.admissionTarget=null;
        factory.evictionScan=null;
        factory.evictionBest=null;
        factory.evictionBestDistance=-1;
        factory.admissionBlocked=false;
    }

    private static boolean hasNearDeferred(Factory factory) {
        synchronized(factory.deferred) {
            for(Entry entry:factory.deferred.values())
                if(nearCell(entry.cell.x,entry.cell.z))return true;
        }
        return false;
    }

    private static Entry nearestDeferred(Factory factory) {
        Entry nearest=null;long nearestDistance=Long.MAX_VALUE;
        synchronized(factory.deferred) {
            for(Entry entry:factory.deferred.values()) {
                if(!nearCell(entry.cell.x,entry.cell.z))continue;
                long distance=distanceSquared(entry.cell.x,entry.cell.z,cameraX,cameraZ);
                if(distance<nearestDistance){nearestDistance=distance;nearest=entry;}
            }
        }
        return nearest;
    }

    private static boolean promoteDeferred(Factory factory,Entry entry) {
        synchronized(factory.contexts) {
            if(factory.contexts.size()>=MAX_CONTEXTS)return false;
            synchronized(factory.deferred) {
                if(factory.deferred.remove(entry.cell)!=entry)return false;
                factory.deferredFarthest=null;
            }
            Entry current=factory.contexts.get(entry.cell);
            if(current!=null) {
                for(long section:entry.sections)if(current.sections.size()<65536)current.sections.add(section);
                current.needsRefresh|=entry.needsRefresh;
                current.deferredSectionsTruncated|=entry.deferredSectionsTruncated;
                removeChunkIndex(factory,entry);
                retireEntry(entry);
                entry.deferred=false;
                factory.admissionTarget=null;
                factory.evictionScan=null;
                if(outdated(current))enqueue(factory,current);
                else if(current.needsRefresh)refresh(current);
                return true;
            }
            entry.deferred=false;
            entry.retired=false;
            entry.touched=ticks;
            registerContext(factory,entry);
        }
        factory.admissionTarget=null;
        factory.evictionScan=null;
        factory.admissionBlocked=false;
        if(outdated(entry))enqueue(factory,entry);
        else if(entry.needsRefresh)refresh(entry);
        if(entry.deferredSectionsTruncated && deferredSweepRevision!=revision) {
            deferredSweepRevision=revision;
            REFRESH.requestResidentSweep();
        }
        progressScan=null;
        return true;
    }

    private static void cancelQueued(Factory factory,Entry entry) {
        if(entry.queued.compareAndSet(true,false)) {
            boolean removed=entry.fallback?factory.fallbackQueue.remove(entry):factory.queue.remove(entry)||factory.farQueue.remove(entry);
            if(removed)(entry.fallback?factory.fallbackSlots:factory.queueSlots).release();
        }
        if(entry.waiting)factory.waiting.remove(entry);
        entry.waiting=false;
        entry.pending=null;
        retireEntry(entry);
    }

    private static void retireEntry(Entry entry) {
        entry.retired=true;
        if(entry.variant!=null)entry.variant.entries.remove(entry);
        entry.variant=null;
    }

    private static Entry evictedCopy(Entry source) {
        Entry copy=new Entry(source.cell,source.state);
        copy.deferred=true;
        copy.revision=-1;
        copy.dirty=source.dirty;
        copy.evaluatedDirty=source.evaluatedDirty;
        copy.touched=source.touched;
        copy.model=source.model;
        copy.nativeModel=source.nativeModel;
        copy.variant=source.variant;
        copy.appearance=source.appearance;
        if(copy.variant!=null)copy.variant.entries.add(copy);
        copy.needsRefresh=source.needsRefresh;
        int count=0;
        for(long section:source.sections) {
            if(count++>=MAX_DEFERRED_SECTIONS)break;
            copy.sections.add(section);
        }
        copy.deferredSectionsTruncated=source.sections.size()>copy.sections.size();
        return copy;
    }

    private static Entry evictContext(Factory factory,Entry victim) {
        Entry copy=evictedCopy(victim);
        synchronized(factory.contexts) {
            if(!factory.contexts.remove(victim.cell,victim))return null;
        }
        removeChunkIndex(factory,victim);
        cancelQueued(factory,victim);
        progressScan=null;
        factory.evictionBest=null;
        return copy;
    }

    private static void processDeferred(Factory factory) {
        Entry target=factory.admissionTarget;
        boolean targetCurrent;
        synchronized(factory.deferred) { targetCurrent=target!=null && factory.deferred.get(target.cell)==target; }
        if(!targetCurrent || !nearCell(target.cell.x,target.cell.z)) {
            target=nearestDeferred(factory);
            factory.admissionTarget=target;
            factory.evictionScan=null;
            factory.evictionBest=null;
            factory.admissionBlocked=false;
        }
        if(target==null)return;
        if(factory.contexts.size()<MAX_CONTEXTS) {
            promoteDeferred(factory,target);
            return;
        }
        long targetDistance=distanceSquared(target.cell.x,target.cell.z,cameraX,cameraZ);
        if(factory.admissionBlocked && factory.blockedContextCount==factory.contexts.size()
                && factory.blockedCell==target.cell
                && ticks<factory.blockedUntilTick
                && cameraX==factory.blockedCameraX && cameraZ==factory.blockedCameraZ)
            return;
        if(factory.evictionScan!=null && (factory.evictionCameraX!=cameraX || factory.evictionCameraZ!=cameraZ)) {
            factory.evictionScan=null;
            factory.evictionBest=null;
            factory.admissionBlocked=false;
        }
        if(factory.evictionScan==null) {
            factory.evictionScan=factory.contexts.values().iterator();
            factory.evictionCameraX=cameraX;factory.evictionCameraZ=cameraZ;
            factory.evictionBest=null;factory.evictionBestDistance=-1;
        }
        long scanTargetDistance=distanceSquared(target.cell.x,target.cell.z,
                factory.evictionCameraX,factory.evictionCameraZ);
        for(int scanned=0;scanned<256 && factory.evictionScan.hasNext();scanned++) {
            Entry candidate=factory.evictionScan.next();
            if(candidate.retired || candidate.deferred || factory.contexts.get(candidate.cell)!=candidate
                    || ticks-candidate.touched<40)continue;
            long distance=distanceSquared(candidate.cell.x,candidate.cell.z,
                    factory.evictionCameraX,factory.evictionCameraZ);
            if(distance>scanTargetDistance+EVICTION_HYSTERESIS_SQUARED
                    && distance>factory.evictionBestDistance) {
                factory.evictionBest=candidate;
                factory.evictionBestDistance=distance;
            }
        }
        if(factory.evictionBest!=null) {
            Entry victim=factory.evictionBest;
            synchronized(factory.contexts) {
                Entry copy=evictContext(factory,victim);
                if(copy!=null && promoteDeferred(factory,target)) {
                    synchronized(factory.deferred) {
                        copy.deferred=true;
                        factory.deferred.put(copy.cell,copy);
                        addChunkIndex(factory,copy);
                        factory.deferredFarthest=null;
                    }
                    resetAdmissionScan(factory);
                    return;
                }
            }
        }
        if(!factory.evictionScan.hasNext()) {
            factory.admissionBlocked=true;
            factory.blockedCell=target.cell;
            factory.blockedCameraX=cameraX;factory.blockedCameraZ=cameraZ;
            factory.blockedContextCount=factory.contexts.size();
            factory.blockedUntilTick=ticks+40;
            factory.evictionScan=null;
        }
    }

    public static int coordinate(int section,int local,int lod) {
        return Math.toIntExact((((long)section<<5)+local)*(1L<<lod)+(lod==0?0:1L<<(lod-1)));
    }

    private static long distanceSquared(int x,int z,int fromX,int fromZ) {
        long dx=(long)x-fromX,dz=(long)z-fromZ;
        return dx*dx+dz*dz;
    }

    private static boolean nearCell(int x,int z) {
        return distanceSquared(x,z,cameraX,cameraZ)<=NEAR_RADIUS_SQUARED;
    }

    private static void rebuildSweep(Factory factory) {
        sweepDirty=false;
        ArrayList<Entry> orderedNear=new ArrayList<>();
        ArrayList<Entry> orderedFar=new ArrayList<>();
        int snapshotX=cameraX,snapshotZ=cameraZ;
        for(Entry entry:factory.contexts.values())
            (nearCell(entry.cell.x,entry.cell.z)?orderedNear:orderedFar).add(entry);
        orderedNear.sort((left,right)->Long.compare(
                distanceSquared(left.cell.x,left.cell.z,snapshotX,snapshotZ),
                distanceSquared(right.cell.x,right.cell.z,snapshotX,snapshotZ)));
        ArrayList<Entry> ordered=new ArrayList<>(orderedNear.size()+orderedFar.size());
        ordered.addAll(orderedNear);
        ordered.addAll(orderedFar);
        sweepOrder=ordered;
        sweep=sweepOrder.iterator();sweepNext=null;
        lastSweepRebuildTick=ticks;
        factory.evictionCameraX=snapshotX;factory.evictionCameraZ=snapshotZ;
        factory.evictionScan=null;factory.admissionBlocked=false;
    }

    private static void restartSweep() {
        sweep=sweepOrder.iterator();sweepNext=null;
    }

    private static void recoverSweep(Factory factory) {
        if(sweep!=null || factory.queueSlots.availablePermits()<=64 || !factory.queueOverflow.getAndSet(false))return;
        if(sweepDirty && (lastSweepRebuildTick==Long.MIN_VALUE || ticks-lastSweepRebuildTick>=40)) {
            rebuildSweep(factory);
        } else {
            restartSweep();
            if(sweepDirty)factory.queueOverflow.set(true);
        }
    }

    private static int queueSize(Factory factory) {
        return factory.queue.size()+factory.farQueue.size()+factory.fallbackQueue.size();
    }

    private static boolean enqueue(Factory factory,Entry entry) {
        if(factory!=active || entry.retired || entry.deferred)return true;
        if(!entry.fallback && !withinRadius(entry.cell.x,entry.cell.z))return true;
        if(!entry.queued.compareAndSet(false,true))return true;
        if((entry.fallback?factory.fallbackSlots:factory.queueSlots).tryAcquire()) {
            if(entry.fallback)factory.fallbackQueue.add(entry);
            else (nearCell(entry.cell.x,entry.cell.z)?factory.queue:factory.farQueue).add(entry);
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
        if(!TfcCompatConfig.enabled() || level==null)return;
        ensureWorld(level);
        captureSnapshot(x,z,snapshot);
    }

    public static synchronized void captureImported(Object engine,int x,int z,float[] snapshot) {
        if(!TfcCompatConfig.enabled())return;
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
        day=Long.MIN_VALUE;month=Long.MIN_VALUE;revision++;climateRevision=0;sweep=null;sweepOrder=List.of();sweepDirty=false;lastSweepRebuildTick=Long.MIN_VALUE;clearRemovedProgress();
        lastCalendarTick=Long.MIN_VALUE;desiredCalendar=null;
        sweepNext=null;dirtyEntries=null;dirtyNext=null;pruning=null;dirtyOverflow.set(false);
        dirtyChunks.clear();REFRESH.reset();
        forceMeshes=false;progressWasWorking=false;
        progressScan=null;progress=new long[8];progressChunks.clear();progressEpoch=Long.MIN_VALUE;progressStarted=System.nanoTime();
        climateArrived.set(false);
    }

    public static void tick(Object level) {
        if(!TfcCompatConfig.enabled()) {
            updateEnabledState(level);
            tickDisabled(level);
            return;
        }
        updateEnabledState(level);
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
            boolean rebuildAppearanceSweep=false;
            if(day!=currentDay || month!=currentMonth || calendar[0]<lastCalendarTick) {
                day=currentDay;month=currentMonth;revision++;
                desiredCalendar=calendar.clone();
                progressStarted=System.nanoTime();progressScan=null;
                rebuildAppearanceSweep=true;
            }
            lastCalendarTick=calendar[0];
            recoverSweep(factory);
            if(climateRevision!=climate.loadRevision() || sweep==null && dirtyOverflow.getAndSet(false)) {
                climateRevision=climate.loadRevision();revision++;
                rebuildAppearanceSweep=true;
                climateArrived.set(true);
            }
            if(rebuildAppearanceSweep)rebuildSweep(factory);
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
                    if(dirtyNext.deferred) {
                        dirtyNext.dirty++;
                        dirtyNext=null;
                        continue;
                    }
                    dirtyNext.dirty++;
                }
                if(!enqueue(factory,dirtyNext))break;
                dirtyNext=null;
            }
            if(sweep!=null) {
                for(int i=0;i<256;i++) {
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
                if(entry==null)entry=factory.farQueue.poll();
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
            prune(factory);
            processDeferred(factory);
            drainForcedMeshes(factory);
            REFRESH.tick(renderer);
            processRecolors(factory);
            updateProgress(factory);
            if(ticks%100==0)climate.flushAsync();
            if(ticks%600==0)LOG.debug("TFC LoDs: climate={}, contexts={}, deferred={}, fallbacks={}, geometryFamilies={}, variants={}, evaluating={}, waiting={}, remesh={}",
                    climate.size(),factory.contexts.size(),factory.deferred.size(),factory.fallbacks.size(),factory.families.size(),factory.variants.size(),queueSize(factory),factory.waiting.size(),REFRESH.pendingCount());
        } catch(ReflectiveOperationException | RuntimeException failure) { warn(failure); }
    }

    public static synchronized boolean updateEnabledState(Object level) {
        boolean enabled=TfcCompatConfig.enabled();
        compatFastEnabled=enabled;
        if(enabled)ensureWorld(level);
        if(!configSeen) {
            configSeen=true;
            if(!enabled && compatWasEnabled) {
                disableCompat();
                rendererReloadRequested=true;
                return true;
            }
            return false;
        }
        if(!enabled) {
            boolean changed=compatWasEnabled;
            disableCompat();
            if(changed)rendererReloadRequested=true;
            return changed || rendererReloadRequested;
        }
        if(!compatWasEnabled) {
            enableCompat();
            rendererReloadRequested=true;
            return true;
        }
        return rendererReloadRequested;
    }

    public static synchronized void rendererReloaded() {
        rendererReloadRequested=false;
        if(!TfcCompatConfig.enabled() || world==null) {
            active=null;
            REFRESH.reset();
        }
    }

    private static boolean commitReady(Factory factory,Entry entry) {
        Variant variant=entry.pending;
        if(variant==null || entry.retired || factory!=active){entry.waiting=false;return true;}
        RecolorPlan recolor=factory.pendingByRequest.get(variant.request);
        if(recolor!=null && recolor.replacement==variant)return false;
        if(outdated(entry)) {
            enqueue(factory,entry);
            return false;
        }
        Variant activeVariant=factory.variants.get(variant.request-FIRST_VARIANT);
        if(activeVariant!=variant) {
            variant=nearestPaletteVariant(factory,variant.family,entry.appearance);
            if(variant==null) {
                entry.pending=null;entry.waiting=false;entry.model=entry.nativeModel;
                if(entry.variant!=null)entry.variant.entries.remove(entry);
                entry.variant=null;refresh(entry);return true;
            }
            entry.pending=variant;
        }
        int model=factory.mappings[variant.request];
        if(model<0 || !factory.uploaded.contains(model)
                || factory.uploadedGenerations.getOrDefault(variant.request,Long.MIN_VALUE)!=variant.generation)return false;
        int previousModel=entry.model;
        entry.model=model;entry.pending=null;entry.waiting=false;
        bindEntryVariant(entry,variant);
        if(previousModel!=model || entry.needsRefresh)refresh(entry);
        return true;
    }

    private static void bindEntryVariant(Entry entry,Variant variant) {
        Variant previous=entry.variant;
        if(previous==variant)return;
        if(previous!=null)previous.entries.remove(entry);
        entry.variant=variant;
        variant.entries.add(entry);
        variant.lastUsed=ticks;
    }

    private static void fallbackFailedVariant(Factory factory,Variant variant) {
        factory.palette.remove(variant.appearance(),variant);
        int previousModel=variant.modelId;
        if(previousModel>=0)factory.modelIdOwners.remove(previousModel,variant);
        variant.modelId=-1;
        factory.mappings[variant.request]=variant.nativeModelId;
        factory.uploaded.add(variant.nativeModelId);
        factory.uploadedGenerations.remove(variant.request);
        for(Entry entry:new ArrayList<>(factory.waiting))if(entry.pending==variant) {
            entry.pending=null;entry.waiting=false;
            entry.model=entry.nativeModel;
            if(entry.variant!=null)entry.variant.entries.remove(entry);
            entry.variant=null;refresh(entry);
        }
        factory.waiting.removeIf(entry->!entry.waiting);
    }

    private static void drainForcedMeshes(Factory factory) {
        if(forceMeshes && sweep==null && dirtyNext==null && dirtyChunks.isEmpty()
                && factory.queue.isEmpty() && factory.farQueue.isEmpty() && factory.fallbackQueue.isEmpty()
                && factory.waiting.isEmpty() && !hasNearDeferred(factory)) {
            REFRESH.requestResidentSweep();
            forceMeshes=false;
        }
    }

    private static void refresh(Entry entry) {
        for(long section:entry.sections)REFRESH.request(section);
        entry.needsRefresh=false;
    }

    private static boolean withinRadius(int x,int z) {
        long dx=(long)x-cameraX,dz=(long)z-cameraZ;return dx*dx+dz*dz<=512L*512;
    }
    private static void updateProgress(Factory factory) {
        for(int i=0;i<128;i++) {
            Long removed=removedProgressChunks.poll();
            if(removed==null)break;
            removedProgressSet.remove(removed);
            removeProgressChunk(removed);
        }
        if(progressEpoch!=revision){progressEpoch=revision;progressChunks.clear();progressDone=0;progressScan=null;}
        if(progressScan==null)progressScan=factory.byChunk.entrySet().iterator();
        for(int i=0;i<128 && progressScan.hasNext();i++) {
            var chunk=progressScan.next();long key=chunk.getKey();
            if(!withinRadius((int)(key>>32),(int)key)){removeProgressChunk(key);continue;}
            boolean complete=true,present=false;
            for(Entry entry:chunk.getValue())if(!entry.retired && (!entry.deferred || nearCell(entry.cell.x,entry.cell.z))){
                present=true;
                if(forceMeshes||outdated(entry)||entry.pending!=null||entry.sections.stream().anyMatch(section->!REFRESH.complete(section)))complete=false;
            }
            if(present){Boolean old=progressChunks.put(key,complete);if(Boolean.TRUE.equals(old))progressDone--;if(complete)progressDone++;}
            else removeProgressChunk(key);
        }
        if(!progressScan.hasNext())progressScan=null;
        int pending=REFRESH.pendingCount();
        boolean working=forceMeshes||sweep!=null||dirtyNext!=null||!dirtyChunks.isEmpty()||!factory.queue.isEmpty()||!factory.farQueue.isEmpty()||!factory.fallbackQueue.isEmpty()||!factory.waiting.isEmpty()||hasNearDeferred(factory)||pending>0||progressDone<progressChunks.size();
        progress=new long[]{revision,progressDone,progressChunks.size(),factory.unknown.size(),working?1:0,(System.nanoTime()-progressStarted)/1_000_000,pending,
                progressScan==null?1:0};
        if(progressWasWorking&&!working)LOG.debug("TFC LoD refresh queues drained: revision={}, sampled chunks={}/{}, unavailable climate={}, submitted={}, accepted={}, changed={}, unchanged={}",
                revision,progressDone,progressChunks.size(),factory.unknown.size(),REFRESH.submittedCount(),REFRESH.acceptedCount(),factory.changed,factory.unchanged);
        progressWasWorking=working;
    }
    private static void removeProgressChunk(long key){if(Boolean.TRUE.equals(progressChunks.remove(key)))progressDone--;}
    private static void clearRemovedProgress(){removedProgressChunks.clear();removedProgressSet.clear();}
    public static long[] refreshProgress(){return progress.clone();}

    public static String status() {
        Factory factory=active;
        TfcClimateStore store=climate;
        if(!TfcCompatConfig.enabled() || factory==null || world!=factory.level)return "TFC seasonal LoDs: inactive";
        return "TFC seasonal LoDs: day="+day+", revision="+revision
                +", climate="+(store==null?0:store.size())+", contexts="+factory.contexts.size()
                +", fallbacks="+factory.fallbacks.size()
                +", geometryFamilies="+factory.families.size()+", variants="+factory.variants.size()+"/"+MAX_VARIANTS
                +", recolors="+factory.recolorPlans+"/"+factory.recolorQueued+"/"+factory.recolorAcks
                +"/"+factory.recolorEnqueueAttempts
                +", recolorLockMisses="+factory.recolorLockMisses+", recolorAddRejects="+factory.recolorAddRejects
                +", recolorUploadRejects="+factory.recolorUploadRejects+", recolorCapacityRejects="+factory.recolorCapacityRejects
                +", recolorCancels="+factory.recolorCancels
                +", deferred="+factory.deferred.size()+", evaluating="+queueSize(factory)+", waiting="+factory.waiting.size()
                +", remesh="+REFRESH.pendingCount()+", submitted="+REFRESH.submittedCount()+", accepted="+REFRESH.acceptedCount()
                +", changed="+factory.changed+", unchanged="+factory.unchanged;
    }

    public static boolean forceRefresh() {
        if(!TfcCompatConfig.enabled())return false;
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
        int colour0=quantizeTint((int)appearance[1]);
        int colour1=quantizeTint((int)appearance[2]);
        Appearance key=new Appearance(entry.state,appearance[0],colour0,colour1);
        Variant variant=factory.palette.get(key);
        if(variant==null) {
            GeometryFamily family=new GeometryFamily(entry.state,appearance[0]);
            List<Variant> allFamilySlots=factory.families.get(family);
            List<Variant> familySlots=allFamilySlots==null?null:allFamilySlots.stream()
                    .filter(candidate->candidate.modelId>=0 && factory.uploaded.contains(candidate.modelId)
                            && factory.uploadedGenerations.getOrDefault(candidate.request,Long.MIN_VALUE)==candidate.generation).toList();
            int familySlotCount=allFamilySlots==null?0:allFamilySlots.size();
            RecolorPlan pendingFamily=factory.pendingByFamily.get(family);
            boolean mayAddColour=mayAddColourVariant(factory.variants.size(),familySlotCount);
            boolean mayAddGeometry=mayAddGeometryFamily(factory.variants.size(),familySlotCount);
            if(pendingFamily!=null) {
                variant=nearestPaletteVariant(factory,family,key);
                if(variant==null)variant=pendingFamily.replacement;
            } else if(mayAddColour || mayAddGeometry) {
                if(factory.enqueue==null || !factory.enqueue.tryLock()){enqueue(factory,entry);return;}
                try {
                    variant=new Variant(entry.state,appearance[0],colour0,colour1,FIRST_VARIANT+factory.variants.size(),
                            ++factory.nextVariantGeneration,revision,entry.nativeModel);
                    factory.variants.add(variant);
                    factory.families.computeIfAbsent(family,ignored->new CopyOnWriteArrayList<>()).add(variant);
                    factory.palette.put(key,variant);
                    factory.add.invoke(factory.owner,variant.request);
                    LockSupport.unpark(factory.worker);
                } finally { factory.enqueue.unlock(); }
            } else {
                RecolorPlan pending=factory.pendingPalette.get(key);
                if(pending!=null)variant=pending.replacement;
                else if(familySlots!=null && !familySlots.isEmpty()) {
                    RecolorPlan plan=createRecolorPlan(factory,entry,key,familySlots);
                    if(plan==null)variant=nearestPaletteVariant(factory,family,key);
                    else {
                        factory.recolorPlans++;
                        factory.pendingPalette.put(key,plan);
                        factory.pendingByRequest.put(plan.replacement.request,plan);
                        factory.pendingByFamily.put(plan.previous.family,plan);
                        variant=plan.replacement;
                    }
                    if(variant==null) {
                        entry.revision=revision;entry.evaluatedDirty=entry.dirty;entry.pending=null;
                        if(entry.variant==null || !entry.variant.family.equals(family)) {
                            if(entry.variant!=null)entry.variant.entries.remove(entry);
                            entry.variant=null;entry.model=entry.nativeModel;refresh(entry);
                        }
                        return;
                    }
                } else {
                    entry.revision=revision;entry.evaluatedDirty=entry.dirty;entry.pending=null;
                    if(entry.variant!=null)entry.variant.entries.remove(entry);
                    entry.variant=null;
                    entry.model=entry.nativeModel;
                    refresh(entry);
                    if(!factory.geometryOverflowWarned) {
                        factory.geometryOverflowWarned=true;
                        LOG.warn("TFC LoD geometry-family reserve is exhausted; unsupported families retain their native models");
                    }
                    return;
                }
            }
        }
        variant.lastUsed=ticks;
        entry.revision=revision;
        entry.evaluatedDirty=entry.dirty;
        entry.appearance=key;
        int model=factory.mappings[variant.request];
        if(model>=0 && entry.model==model)factory.unchanged++;else factory.changed++;
        RecolorPlan pending=factory.pendingByRequest.get(variant.request);
        if(pending==null && model>=0 && factory.uploaded.contains(model)) {
            if(entry.model!=model) { entry.model=model;refresh(entry); }
            else if(entry.needsRefresh)refresh(entry);
            bindEntryVariant(entry,variant);
            entry.pending=null;
        } else {
            entry.pending=variant;
            if(!entry.waiting){entry.waiting=true;factory.waiting.addLast(entry);}
        }
    }

    private static RecolorPlan createRecolorPlan(Factory factory,Entry requester,Appearance key,List<Variant> familySlots) {
        Variant victim=null;
        for(Variant candidate:familySlots)if(candidate.modelId>=0 && factory.uploaded.contains(candidate.modelId)
                && factory.uploadedGenerations.getOrDefault(candidate.request,Long.MIN_VALUE)==candidate.generation
                && candidate.lastRecoloredRevision!=revision
                && !factory.pendingByRequest.containsKey(candidate.request)
                && (victim==null || candidate.entries.size()<victim.entries.size()
                || candidate.entries.size()==victim.entries.size() && candidate.lastUsed<victim.lastUsed))victim=candidate;
        if(victim==null)return null;
        if(victim.entries.size()>MAX_RECOLOR_SECTIONS)return null;
        List<Entry> owners=new ArrayList<>(victim.entries);
        Variant replacement=new Variant(key.state,key.model,key.colour0,key.colour1,victim.request,
                ++factory.nextVariantGeneration,revision,victim.nativeModelId);
        Variant selectedVictim=victim;
        List<Variant> alternatives=familySlots.stream().filter(candidate->candidate!=selectedVictim
                && !factory.pendingByRequest.containsKey(candidate.request) && candidate.modelId>=0
                && factory.uploaded.contains(candidate.modelId)
                && factory.uploadedGenerations.getOrDefault(candidate.request,Long.MIN_VALUE)==candidate.generation).toList();
        if(!alternatives.isEmpty() && REFRESH.trackedCount()>=MAX_CONTEXTS)return null;
        LinkedHashSet<Long> sections=new LinkedHashSet<>();
        if(!alternatives.isEmpty())for(Entry owner:owners)for(long section:owner.sections) {
            sections.add(section);
            if(sections.size()>MAX_RECOLOR_SECTIONS)return null;
        }
        for(Entry owner:owners) {
            Appearance desired=owner.appearance==null?victim.appearance():owner.appearance;
            Variant nearest=nearestPaletteVariant(factory,victim.family,desired);
            if(nearest==victim || nearest!=null && !alternatives.contains(nearest))nearest=null;
            if(nearest!=null) {
                owner.variant=nearest;owner.model=nearest.modelId;
                victim.entries.remove(owner);nearest.entries.add(owner);
                nearest.lastUsed=ticks;
                owner.needsRefresh=false;
            }
        }
        factory.palette.entrySet().removeIf(item->item.getValue()==selectedVictim);
        factory.pendingPalette.entrySet().removeIf(item->item.getValue().previous==selectedVictim);
        return new RecolorPlan(victim,replacement,List.copyOf(sections));
    }

    private static Variant nearestVariant(List<Variant> candidates,int colour0,int colour1,Variant excluded) {
        Variant best=null;long bestDistance=Long.MAX_VALUE;
        for(Variant candidate:candidates)if(candidate!=excluded) {
            long distance=colourDistance(colour0,candidate.colour0)+colourDistance(colour1,candidate.colour1);
            if(distance<bestDistance){best=candidate;bestDistance=distance;}
        }
        return best;
    }

    private static Variant nearestPaletteVariant(Factory factory,GeometryFamily family,Appearance desired) {
        List<Variant> slots=factory.families.get(family);
        if(slots==null || slots.isEmpty())return null;
        List<Variant> ready=slots.stream().filter(candidate->candidate.modelId>=0
                && factory.uploaded.contains(candidate.modelId)
                && factory.uploadedGenerations.getOrDefault(candidate.request,Long.MIN_VALUE)==candidate.generation).toList();
        List<Variant> current=ready.stream().filter(candidate->candidate.revision==revision
                || candidate.lastRecoloredRevision==revision).toList();
        List<Variant> eligible=current.isEmpty()?ready:current;
        return nearestVariant(eligible,desired==null?-1:desired.colour0,desired==null?-1:desired.colour1,null);
    }

    private static long colourDistance(int a,int b) {
        if(a==-1||b==-1)return a==b?0:3L*255*255;
        long r=((a>>>16)&255)-((b>>>16)&255),g=((a>>>8)&255)-((b>>>8)&255),bl=(a&255)-(b&255);
        return r*r+g*g+bl*bl;
    }

    static boolean mayAddColourVariant(int allocated,int familySlots) {
        return familySlots>0 && familySlots<MAX_COLOR_VARIANTS_PER_FAMILY
                && allocated<MAX_VARIANTS-GEOMETRY_FAMILY_RESERVE;
    }

    static boolean mayAddGeometryFamily(int allocated,int familySlots) {
        return familySlots==0 && allocated<MAX_VARIANTS;
    }

    private static void processRecolors(Factory factory) {
        int enqueueAttempts=0;
        int requests=128;
        for(RecolorPlan plan:new ArrayList<>(factory.pendingByRequest.values())) {
            if(plan.replacement.revision!=revision && !plan.queued && !plan.uploading) {
                cancelRecolor(factory,plan);
                continue;
            }
            while(requests>0 && plan.requested<plan.refreshSections.size()) {
                REFRESH.request(plan.refreshSections.get(plan.requested++));
                requests--;
            }
            if(plan.queued || plan.uploading || enqueueAttempts>=4)continue;
            enqueueAttempts++;
            factory.recolorEnqueueAttempts++;
            queueRecolor(factory,plan);
        }
    }

    private static void queueRecolor(Factory factory,RecolorPlan plan) {
        if(factory!=active || factory.enqueue==null)return;
        if(!factory.enqueue.tryLock()){factory.recolorLockMisses++;return;}
        int request=plan.replacement.request,target=plan.previous.modelId;
        try {
            List<Map.Entry<?,?>> entries=modelEntries(factory);
            Map.Entry<?,?> previous=onlyEntryFor(entries,target);
            if(previous==null || previous.getKey()!=plan.previous.modelEntryKey) {
                cancelRecolor(factory,plan);
                return;
            }
            if(entries.size()>=MAX_VOXY_MODEL_IDS) {
                factory.recolorCapacityRejects++;
                cancelRecolor(factory,plan);
                return;
            }
            factory.variants.set(request-FIRST_VARIANT,plan.replacement);
            factory.mappings[request]=-1;
            factory.uploaded.remove(target);
            factory.uploadedGenerations.remove(request);
            boolean added=(boolean)factory.add.invoke(factory.owner,request);
            if(!added) {
                factory.recolorAddRejects++;
                factory.variants.set(request-FIRST_VARIANT,plan.previous);
                factory.mappings[request]=target;
                factory.uploaded.add(target);
                factory.uploadedGenerations.put(request,plan.previous.generation);
                return;
            }
            plan.queued=true;
            factory.recolorQueued++;
            LockSupport.unpark(factory.worker);
        } catch(ReflectiveOperationException | RuntimeException failure) {
            warn(failure);
            factory.variants.set(request-FIRST_VARIANT,plan.previous);
            factory.mappings[request]=target;
            factory.uploaded.add(target);
            factory.uploadedGenerations.put(request,plan.previous.generation);
        } finally { factory.enqueue.unlock(); }
    }

    private static void commitRecolor(Factory factory,RecolorPlan plan) {
        if(factory.pendingByRequest.get(plan.replacement.request)!=plan)return;
        Variant replacement=plan.replacement,previous=plan.previous;
        List<Variant> family=factory.families.get(previous.family);
        if(family!=null) {
            int index=family.indexOf(previous);
            if(index>=0)family.set(index,replacement);
        }
        for(Entry entry:previous.entries)if(entry.variant==previous) {
            previous.entries.remove(entry);
            entry.variant=replacement;
            replacement.entries.add(entry);
        }
        replacement.lastUsed=ticks;
        replacement.lastRecoloredRevision=revision;
        factory.recolorAcks++;
        factory.palette.put(replacement.appearance(),replacement);
        factory.pendingPalette.remove(replacement.appearance(),plan);
        factory.pendingByRequest.remove(replacement.request,plan);
        factory.pendingByFamily.remove(previous.family,plan);
        factory.modelIdOwners.put(replacement.modelId,replacement);
    }

    private static void cancelRecolor(Factory factory,RecolorPlan plan) {
        factory.recolorCancels++;
        Variant previous=plan.previous,replacement=plan.replacement;
        if(replacement.revision==revision)previous.lastRecoloredRevision=revision;
        factory.variants.set(previous.request-FIRST_VARIANT,previous);
        factory.modelIdOwners.put(previous.modelId,previous);
        factory.mappings[previous.request]=previous.modelId;
        factory.uploaded.add(previous.modelId);
        factory.uploadedGenerations.put(previous.request,previous.generation);
        factory.pendingPalette.remove(replacement.appearance(),plan);
        factory.pendingByRequest.remove(previous.request,plan);
        factory.pendingByFamily.remove(previous.family,plan);
        factory.palette.put(previous.appearance(),previous);
        restorePlanEntries(factory,plan);
    }

    private static void restorePlanEntries(Factory factory,RecolorPlan plan) {
        Variant previous=plan.previous,replacement=plan.replacement;
        boolean stale=plan.replacement.revision!=revision;
        List<Variant> family=factory.families.get(previous.family);
        List<Variant> alternatives=family==null?List.of(previous):family.stream().filter(candidate->candidate!=replacement).toList();
        for(Entry entry:new ArrayList<>(factory.waiting))if(entry.pending==replacement) {
            Variant nearest=nearestPaletteVariant(factory,previous.family,entry.appearance);
            if(nearest!=null && !alternatives.contains(nearest))nearest=null;
            entry.pending=null;entry.waiting=false;
            if(nearest!=null) {
                bindEntryVariant(entry,nearest);entry.model=nearest.modelId;refresh(entry);
            } else {
                if(entry.variant!=null)entry.variant.entries.remove(entry);
                entry.variant=null;entry.model=-1;
            }
            entry.revision=stale?Long.MIN_VALUE:revision;entry.evaluatedDirty=entry.dirty;
            if(stale)enqueue(factory,entry);
        }
        factory.waiting.removeIf(entry->!entry.waiting);
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
                retireEntry(entry);
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
    private static int quantizeTint(int rgb) { return rgb==-1?-1:quantize(rgb); }

    private static synchronized void enableCompat() {
        compatWasEnabled=true;
        compatFastEnabled=true;
        Factory factory=active;
        if(factory==null)return;
        revision++;
        forceMeshes=true;
        progressStarted=System.nanoTime();progressScan=null;progressChunks.clear();progressDone=0;
        sweep=factory.contexts.values().iterator();sweepNext=null;
        for(Entry entry:factory.contexts.values()) { entry.revision=-1; entry.evaluatedDirty=0; entry.pending=null; entry.waiting=false; }
        factory.fallbackSweep=null;factory.unknownScan=null;factory.waiting.clear();
    }

    private static synchronized void disableCompat() {
        if(!compatWasEnabled && !TfcCompatConfig.enabled())return;
        compatWasEnabled=false;
        compatFastEnabled=false;
        BAKE.remove();MESH.remove();MESH_STAMP.remove();
        Factory factory=active;
        if(factory!=null) {
            REFRESH.requestResidentSweep();
            Entry entry;
            while((entry=factory.queue.poll())!=null){entry.queued.set(false);factory.queueSlots.release();}
            while((entry=factory.farQueue.poll())!=null){entry.queued.set(false);factory.queueSlots.release();}
            while((entry=factory.fallbackQueue.poll())!=null){entry.queued.set(false);factory.fallbackSlots.release();}
            synchronized(factory.deferred) {
                for(Entry deferred:factory.deferred.values()) {
                    deferred.pending=null;deferred.waiting=false;retireEntry(deferred);
                    removeChunkIndex(factory,deferred);
                }
                factory.deferred.clear();
                factory.deferredFarthest=null;
                factory.admissionTarget=null;
                factory.evictionScan=null;
                factory.evictionBest=null;
                factory.admissionBlocked=false;
            }
            for(Entry value:factory.contexts.values()) {
                value.pending=null;value.waiting=false;value.model=-1;value.revision=-1;
                if(value.variant!=null)value.variant.entries.remove(value);value.variant=null;
            }
            for(Entry value:factory.fallbacks.values()) {
                value.pending=null;value.waiting=false;value.model=-1;value.revision=-1;
                if(value.variant!=null)value.variant.entries.remove(value);value.variant=null;
            }
            factory.fallbackSweep=null;factory.unknownScan=null;
            factory.waiting.clear();
        }
        progress=new long[8];progressScan=null;progressChunks.clear();progressDone=0;clearRemovedProgress();
        forceMeshes=false;sweep=null;sweepOrder=List.of();sweepDirty=false;lastSweepRebuildTick=Long.MIN_VALUE;sweepNext=null;dirtyEntries=null;dirtyNext=null;pruning=null;
        dirtyChunks.clear();dirtyOverflow.set(false);climateArrived.set(false);
        if(adapter!=null)try{adapter.tickClimate.invoke(null);}catch(ReflectiveOperationException|RuntimeException ignored){}
        if(climate!=null)climate.close();
        climate=null;world=null;
    }

    private static void tickDisabled(Object level) {
        Factory factory=active;
        if(factory==null || level==null || factory.level!=level)return;
        try {
            Object renderer=Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem",false,level.getClass().getClassLoader())
                    .getMethod("getNullable").invoke(null);
            REFRESH.tick(renderer);
        } catch(ReflectiveOperationException|RuntimeException ignored) {}
    }

    private static Object field(Object object,String name) throws ReflectiveOperationException {
        Map<String,Field> fields=FIELDS.get(object.getClass());
        Field field=fields.get(name);
        if(field==null) { field=object.getClass().getDeclaredField(name);field.setAccessible(true);fields.put(name,field); }
        return field.get(object);
    }
    private static void fieldSet(Object object,String name,Object value) throws ReflectiveOperationException {
        Map<String,Field> fields=FIELDS.get(object.getClass());
        Field field=fields.get(name);
        if(field==null){field=object.getClass().getDeclaredField(name);field.setAccessible(true);fields.put(name,field);}
        field.set(object,value);
    }
    private static List<Map.Entry<?,?>> modelEntries(Factory factory) throws ReflectiveOperationException {
        Object value=field(factory.owner,"modelTexture2id");
        if(!(value instanceof Map<?,?> map))throw new IllegalStateException("Unexpected Voxy model palette");
        return new ArrayList<>(map.entrySet());
    }
    private static Map.Entry<?,?> onlyEntryFor(List<Map.Entry<?,?>> entries,int modelId) {
        Map.Entry<?,?> found=null;
        for(Map.Entry<?,?> entry:entries)if(entry.getValue() instanceof Number id && id.intValue()==modelId){
            if(found!=null)return null;
            found=entry;
        }
        return found;
    }
    private static Map.Entry<?,?> modelEntryForRequest(List<Map.Entry<?,?>> entries,int request) throws ReflectiveOperationException {
        Map.Entry<?,?> found=null;
        int marker=modelMarker(request);
        for(Map.Entry<?,?> entry:entries) {
            Object key=entry.getKey();
            if(key==null || !key.getClass().getSimpleName().equals("ModelEntry"))continue;
            if((int)field(key,"tintingColour")!=marker)continue;
            if(found!=null)return null;
            found=entry;
        }
        return found;
    }
    private static Map.Entry<?,?> modelEntryForRequest(List<Map.Entry<?,?>> entries,int request,int modelId,Object excludedKey) throws ReflectiveOperationException {
        Map.Entry<?,?> found=null;
        int marker=modelMarker(request);
        for(Map.Entry<?,?> entry:entries) {
            Object key=entry.getKey();
            if(key==null || key==excludedKey || !key.getClass().getSimpleName().equals("ModelEntry"))continue;
            if((int)field(key,"tintingColour")!=marker)continue;
            if(!(entry.getValue() instanceof Number id) || id.intValue()!=modelId)continue;
            if(found!=null)return null;
            found=entry;
        }
        return found;
    }
    private static Map.Entry<?,?> modelEntryForRequest(List<Map.Entry<?,?>> entries,int request,Object excludedKey) throws ReflectiveOperationException {
        Map.Entry<?,?> found=null;
        int marker=modelMarker(request);
        for(Map.Entry<?,?> entry:entries) {
            Object key=entry.getKey();
            if(key==null || key==excludedKey || !key.getClass().getSimpleName().equals("ModelEntry"))continue;
            if((int)field(key,"tintingColour")!=marker)continue;
            if(found!=null)return null;
            found=entry;
        }
        return found;
    }
    private static void warn(Throwable failure) {
        if(!warned){warned=true;LOG.warn("TFC seasonal LoDs could not complete an update; preserving existing geometry",failure);}
    }

    private record Adapter(Method available,Method isLeaf,Method supports,Method normalize,Method level,Method evaluate,Method day,Method calendar,Method camera,Method requestClimate,Method tickClimate) {
        Adapter(Class<?> type) throws ReflectiveOperationException {
            this(type.getMethod("available",ClassLoader.class),type.getMethod("isLeaf",Object.class),type.getMethod("supports",Object.class),type.getMethod("normalize",Object.class),type.getMethod("clientLevel"),
                    type.getMethod("evaluate",Object.class,int.class,int.class,int.class,Object.class,Object.class),
                    type.getMethod("calendarDay"),type.getMethod("calendarSnapshot"),type.getMethod("cameraChunk"),
                    type.getMethod("requestClimate",Object.class,int.class,int.class),type.getMethod("tickClimate"));
        }
    }
    private record Cell(int state,int x,int z,int y) {}
    private record Appearance(Object state,Object model,int colour0,int colour1) {}
    private record GeometryFamily(Object state,Object model) {}
    private static final class Variant {
        final Object state,model;final int colour0,colour1,request;final GeometryFamily family;final long generation,revision;
        final int nativeModelId;
        final Set<Entry> entries=ConcurrentHashMap.newKeySet();
        volatile long lastUsed;
        volatile long lastRecoloredRevision=Long.MIN_VALUE;
        volatile int modelId=-1;
        volatile byte[] modelBytes;
        volatile byte[] textureAlpha;
        volatile Object modelEntryKey;
        Variant(Object state,Object model,int colour0,int colour1,int request,long generation,long revision,int nativeModelId) {
            this.state=state;this.model=model;this.colour0=colour0;this.colour1=colour1;this.request=request;
            this.family=new GeometryFamily(state,model);this.generation=generation;this.revision=revision;this.nativeModelId=nativeModelId;
        }
        Appearance appearance(){return new Appearance(state,model,colour0,colour1);}
    }
    private static final class RecolorPlan {
        final Variant previous;volatile Variant replacement;final List<Long> refreshSections;
        int requested;
        volatile boolean queued;volatile boolean uploading;
        RecolorPlan(Variant previous,Variant replacement,List<Long> refreshSections) {
            this.previous=previous;this.replacement=replacement;this.refreshSections=refreshSections;
        }
    }
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
        volatile boolean deferred;
        volatile boolean deferredSectionsTruncated;
        volatile boolean needsRefresh;
        volatile int model=-1;
        volatile int nativeModel=-1;
        volatile Variant variant;
        volatile Appearance appearance;
        volatile Variant pending;volatile boolean waiting;
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
        final Map<Cell,Entry> deferred=new LinkedHashMap<>();
        volatile long deferredScanTick=Long.MIN_VALUE;
        Entry deferredFarthest;
        long deferredFarthestDistance=-1;
        Iterator<Entry> evictionScan;
        Entry admissionTarget;
        Entry evictionBest;
        Cell blockedCell;
        long evictionBestDistance=-1;
        int evictionCameraX,evictionCameraZ,blockedContextCount,blockedCameraX,blockedCameraZ;
        long blockedUntilTick;
        boolean admissionBlocked;
        Iterator<Entry> fallbackSweep;
        final byte[] appearanceKinds=new byte[FIRST_VARIANT];
        final ConcurrentMap<Cell,Entry> contexts=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,Set<Entry>> byChunk=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,Set<Long>> unknown=new ConcurrentHashMap<>();
        Iterator<Map.Entry<Long,Set<Long>>> unknownScan;
        final Queue<Entry> queue=new ConcurrentLinkedQueue<>();
        final Queue<Entry> farQueue=new ConcurrentLinkedQueue<>();
        final Queue<Entry> fallbackQueue=new ConcurrentLinkedQueue<>();
        final Semaphore queueSlots=new Semaphore(4096);
        final Semaphore fallbackSlots=new Semaphore(256);
        final AtomicBoolean queueOverflow=new AtomicBoolean();
        final Map<Appearance,Variant> palette=new ConcurrentHashMap<>();
        final List<Variant> variants=new CopyOnWriteArrayList<>();
        final Map<GeometryFamily,List<Variant>> families=new ConcurrentHashMap<>();
        final Map<Appearance,RecolorPlan> pendingPalette=new ConcurrentHashMap<>();
        final Map<Integer,RecolorPlan> pendingByRequest=new ConcurrentHashMap<>();
        final Map<GeometryFamily,RecolorPlan> pendingByFamily=new ConcurrentHashMap<>();
        final Map<Integer,Variant> modelIdOwners=new ConcurrentHashMap<>();
        final ConcurrentLinkedDeque<Entry> waiting=new ConcurrentLinkedDeque<>();
        final Set<Integer> uploaded=ConcurrentHashMap.newKeySet();
        final Map<Integer,Long> uploadedGenerations=new ConcurrentHashMap<>();
        final Set<Integer> uploading=new HashSet<>();
        final Set<RecolorPlan> uploadingRecolors=Collections.newSetFromMap(new IdentityHashMap<>());
        long nextVariantGeneration;
        boolean geometryOverflowWarned;
        long recolorPlans,recolorQueued,recolorAcks,recolorEnqueueAttempts,recolorLockMisses,recolorAddRejects,recolorUploadRejects,recolorCapacityRejects,recolorCancels;
        Lock enqueue;Thread worker;java.nio.file.Path storagePath;
        long changed,unchanged;
        Factory(Object owner,int[] mappings,Object level) throws ReflectiveOperationException {
            this.owner=owner;this.mappings=mappings;this.level=level;
            mapper=field(owner,"mapper");
            stateLookup=mapper.getClass().getMethod("getBlockStateFromBlockId",int.class);
            add=owner.getClass().getMethod("addEntry",int.class);
        }
    }
}
