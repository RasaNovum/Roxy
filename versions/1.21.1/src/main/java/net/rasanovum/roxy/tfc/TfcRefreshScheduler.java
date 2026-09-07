package net.rasanovum.roxy.tfc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public final class TfcRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final int MAX_TRACKED = 65_536;
    private final Map<Long, State> tracked = new LinkedHashMap<>();
    private final LinkedHashSet<Long> near = new LinkedHashSet<>(), far = new LinkedHashSet<>();
    private final ArrayDeque<Long> pruning=new ArrayDeque<>();
    private int pending;
    private long ticks;
    private long submittedCount, acceptedCount;
    private long revision = Long.MIN_VALUE;
    private int cameraX, cameraZ;
    private Object renderer;
    private Access access;
    private boolean failed;

    public synchronized void camera(int x, int z) { cameraX=x; cameraZ=z; }
    public synchronized boolean inRange(long key) { return distanceSquared(key)<=512.0*512; }
    private double distanceSquared(long key) {
        int lod=(int)(key>>>60);
        long width=2L<<lod, x=(key<<36>>40)*width, z=(key<<12>>40)*width;
        double dx=Math.max(0,Math.max(x-cameraX,cameraX-(x+width)));
        double dz=Math.max(0,Math.max(z-cameraZ,cameraZ-(z+width)));
        return dx*dx+dz*dz;
    }
    public synchronized void track(long key) {
        if(inRange(key) && tracked.size()<MAX_TRACKED && !tracked.containsKey(key)){tracked.put(key,new State());pruning.add(key);}
    }
    public synchronized void request(long key) {
        track(key);
        State state=tracked.get(key);
        if(state==null || !inRange(key))return;
        if(state.desired==state.completed)pending++;
        state.desired++;
        queue(key,state);
    }
    private void queue(long key, State state) {
        if(state.inFlight || state.desired<=state.completed)return;
        (distanceSquared(key)<=128.0*128?near:far).add(key);
    }
    public synchronized void requestResidentSweep() { for(long key:tracked.keySet())request(key); }
    public synchronized void advanceRevision(long value) {
        if(revision!=value){revision=value;requestResidentSweep();}
    }
    public synchronized long begin(long key) {
        State state=tracked.get(key);
        return state==null?-1:state.desired;
    }
    public synchronized void accepted(long key,long generation) {
        State state=tracked.get(key);
        if(state==null || generation<0)return;
        boolean wasPending=state.desired>state.completed;
        if(generation>state.completed)acceptedCount++;
        state.completed=Math.min(state.desired,Math.max(state.completed,generation));
        if(wasPending && state.completed==state.desired)pending--;
        if(generation>=state.submitted)state.inFlight=false;
        queue(key,state);
    }
    public void tick(Object currentRenderer) {
        if(currentRenderer==null)return;
        try {
            if(renderer!=currentRenderer){renderer=currentRenderer;access=Access.resolve(renderer);failed=false;}
            if(access==null)return;
            ticks++;
            boolean nativeIdle=(int)access.taskCount.invoke(access.service)==0;
            synchronized(this) {
                for(int i=0,n=Math.min(128,pruning.size());i<n;i++) {
                    long key=pruning.removeFirst();
                    if(!tracked.containsKey(key))continue;
                    if(!inRange(key)||!access.watched(key))remove(key);
                    else {
                        State state=tracked.get(key);
                        if(nativeIdle && state.inFlight && ticks-state.submittedTick>=200) {
                            state.inFlight=false;
                            queue(key,state);
                        }
                        pruning.addLast(key);
                    }
                }
            }
            int budget=Math.min(16,Math.max(0,32-(int)access.taskCount.invoke(access.service)));
            for(int inspected=0,emitted=0;inspected<256 && emitted<budget;inspected++) {
                if((int)access.taskCount.invoke(access.service)>=32)break;
                Long key;
                synchronized(this) {
                    key=poll(near);if(key==null)key=poll(far);if(key==null)break;
                    State state=tracked.get(key);
                    if(state==null)continue;
                    if(!inRange(key) || !access.watched(key)){remove(key);continue;}
                    if(state.inFlight || state.desired<=state.completed)continue;
                    state.inFlight=true;
                    state.submitted=state.desired;
                    state.submittedTick=ticks;
                }
                access.clearCache.invoke(access.cache,key.longValue());
                access.remesh.invoke(access.router,key.longValue());
                synchronized(this){submittedCount++;}
                emitted++;
            }
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {
            access=null;
            if(!failed){failed=true;LOGGER.warn("TFC seasonal LoD refresh is unavailable for this Voxy renderer",failure);}
        }
    }
    private static Long poll(LinkedHashSet<Long> queue) {
        Iterator<Long> it=queue.iterator();if(!it.hasNext())return null;Long key=it.next();it.remove();return key;
    }
    private void remove(long key){State s=tracked.remove(key);near.remove(key);far.remove(key);if(s!=null&&s.desired>s.completed)pending--;}
    public synchronized void reset(){tracked.clear();near.clear();far.clear();pruning.clear();pending=0;ticks=0;submittedCount=0;acceptedCount=0;cameraX=0;cameraZ=0;renderer=null;access=null;revision=Long.MIN_VALUE;failed=false;}
    public synchronized boolean complete(long key){State state=tracked.get(key);return state==null||state.desired==state.completed;}
    public synchronized long submittedCount(){return submittedCount;}
    public synchronized long acceptedCount(){return acceptedCount;}
    public synchronized int trackedCount(){return tracked.size();}
    public synchronized int pendingCount(){return pending;}
    public boolean isWatched(long key){try{return inRange(key)&&(access==null||access.watched(key));}catch(ReflectiveOperationException e){return true;}}
    private static final class State {long desired,completed,submitted,submittedTick;boolean inFlight;}
    private record Access(Object router,Object cache,Object service,Method watched,Method remesh,Method clearCache,Method taskCount) {
        boolean watched(long key)throws ReflectiveOperationException{return ((int)watched.invoke(router,key)&1)!=0;}
        static Access resolve(Object renderer)throws ReflectiveOperationException {
            Object manager=field(renderer,"nodeManager"),router=field(manager,"router"),cache=field(manager,"geometryCache"),service=field(renderer,"renderGen");
            return new Access(router,cache,service,router.getClass().getMethod("get",long.class),router.getClass().getMethod("triggerRemesh",long.class),cache.getClass().getMethod("clear",long.class),service.getClass().getMethod("getTaskCount"));
        }
        static Object field(Object object,String name)throws ReflectiveOperationException{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    }
}
