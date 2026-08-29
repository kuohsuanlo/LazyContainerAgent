import java.util.*;
import java.util.function.*;

/**
 * 窮舉小模型探索器 v2(路線 a 的可行性實測)。
 * 兩條執行緒 × 微步驟交錯,BFS/DFS + visited set 跑完「所有交錯」,檢查不變式。
 * 用小組譯器(label)避免手算跳躍位址(v1 就是在這裡出錯的)。
 */
public class LazyModelCheck3 {

    // ── opcode ──
    static final int END=0, LOCK=1, UNLOCK=2, READ_PENDING=3, JZ=4, JNZ=5, FILL=6,
            SET_PENDING=7, SET_RAWID=8, CHECK_COMPLETE=9, SAVE_RAW=10, SAVE_ENCODE=11,
            NEW_ITEMS=12, SET_DECODED=13, JMP=16, READ_DEC=17, SAVE_MERGE=18,
            CAPTURE_LIST=21, SET_DEC=22, MAYBE_COMPLETE=23, SETITEM=24,
            SAVE_MERGE_EMPTYKEY=25, FILL_UNDECODED=26, READ_MASK=27, OR_MASK_WRITE=28, CHECK_SLOT=29;

    static int N;            // 格數
    static int ALL;
    static int[] RAW_A, RAW_B;
    static final int RAW_NONE=0, RAW_IDA=1, RAW_IDB=2;
    static int[] rawOf(int id){ return id==RAW_IDA?RAW_A:id==RAW_IDB?RAW_B:null; }

    static void setSize(int n){
        N=n; ALL=(1<<n)-1;
        RAW_A=new int[n]; for(int i=0;i<n;i++) RAW_A[i]=i+1;   // 每格不同物品
        RAW_B=new int[n]; RAW_B[0]=1;                          // 重新載入後只剩 slot0
    }

    // ── 小組譯器 ──
    static final class Asm {
        final List<int[]> ins = new ArrayList<>();
        int emit(int op,int arg){ ins.add(new int[]{op,arg}); return ins.size()-1; }
        int here(){ return ins.size(); }
        void patch(int at,int target){ ins.get(at)[1]=target; }
        int[] done(){ emit(END,0); int[] r=new int[ins.size()*2];
            for(int i=0;i<ins.size();i++){ r[i*2]=ins.get(i)[0]; r[i*2+1]=ins.get(i)[1]; } return r; }
    }

    // ── 狀態 ──
    static final class St {
        int[][] lists; int listGen, decoded, rawId, owner=-1, depth;
        boolean pending;
        int[] pc=new int[2], loc=new int[2], cap=new int[2];
        int[] saved; String violation;
        St(){ lists=new int[][]{new int[N], new int[N]}; }
        St copy(){ St s=new St();
            s.lists=new int[][]{lists[0].clone(),lists[1].clone()};
            s.listGen=listGen; s.decoded=decoded; s.pending=pending; s.rawId=rawId;
            s.owner=owner; s.depth=depth; s.pc=pc.clone(); s.loc=loc.clone(); s.cap=cap.clone();
            s.saved=saved==null?null:saved.clone(); s.violation=violation; return s; }
        String key(){ return Arrays.deepToString(lists)+listGen+decoded+pending+rawId+owner+depth
                +Arrays.toString(pc)+Arrays.toString(loc)+Arrays.toString(cap)
                +(saved==null?"-":Arrays.toString(saved))+(violation==null?"":"!V"); }
        int[] live(){ return lists[listGen]; }
        /** 邏輯內容:已解碼的格看 items,未解碼且仍 pending 的看 raw。 */
        int[] logical(){
            int[] raw=rawOf(rawId), o=new int[N];
            for(int i=0;i<N;i++)
                o[i]=(pending && raw!=null && ((decoded>>>i)&1)==0 && raw[i]!=0) ? raw[i] : live()[i];
            return o;
        }
    }

    // ── 程式 ──
    /** 現行整箱 ensure:填完才翻旗標,全程持鎖。buggy=先翻旗標且不持鎖(26.2-1)。 */
    static int[] ensureWhole(boolean fixed){
        Asm a=new Asm();
        a.emit(READ_PENDING,0); int j1=a.emit(JZ,0);
        int j2=-1;
        if(fixed){ a.emit(LOCK,0); a.emit(READ_PENDING,0); j2=a.emit(JZ,0); }
        if(!fixed) a.emit(SET_PENDING,0);                 // 舊版:先翻旗標
        a.emit(CAPTURE_LIST,0);
        for(int i=0;i<N;i++) a.emit(FILL,i);              // 逐格填(半填視窗就在這裡)
        a.emit(SET_RAWID,RAW_NONE);
        if(fixed){ a.emit(SET_PENDING,0); a.emit(SET_DECODED,ALL); }
        int unlockPc=a.here();
        if(fixed) a.emit(UNLOCK,0);
        int end=a.here();
        a.patch(j1,end); if(j2>=0) a.patch(j2,unlockPc);
        return a.done();
    }
    /** leaf guard 讀者:看到 pending==false 就直接用清單。 */
    static int[] reader(){
        Asm a=new Asm(); int top=a.here();
        a.emit(READ_PENDING,0); int j=a.emit(JNZ,top); a.emit(CHECK_COMPLETE,0);
        a.patch(j,top); return a.done();
    }
    /** 存檔:pending⟹寫 raw,否則編碼清單。locked=現行(synchronized)。 */
    static int[] saveWhole(boolean locked){
        Asm a=new Asm();
        if(locked) a.emit(LOCK,0);
        a.emit(READ_PENDING,0); int jz=a.emit(JZ,0);
        a.emit(SAVE_RAW,0); int jmp=a.emit(JMP,0);
        int elseAt=a.here(); a.emit(SAVE_ENCODE,0);
        int after=a.here(); if(locked) a.emit(UNLOCK,0);
        a.patch(jz,elseAt); a.patch(jmp,after);
        return a.done();
    }
    /** leaf loadAdditional:入口 clear guard(可選)→ putfield 新清單 → synchronized load。 */
    static int[] reload(boolean guard, boolean resetMask){
        Asm a=new Asm();
        if(guard){ a.emit(LOCK,0); a.emit(SET_PENDING,0); a.emit(SET_RAWID,RAW_NONE);
            if(resetMask) a.emit(SET_DECODED,0); a.emit(UNLOCK,0); }
        a.emit(NEW_ITEMS,0);                                  // 裸 putfield(鎖外)
        a.emit(LOCK,0); a.emit(SET_RAWID,RAW_IDB);
        if(resetMask) a.emit(SET_DECODED,0);
        a.emit(SET_PENDING,1); a.emit(UNLOCK,0);
        return a.done();
    }
    /** 逐格:ensureSlot(i)。 */
    static void ensureSlotInto(Asm a,int i){
        a.emit(READ_PENDING,0); int jz=a.emit(JZ,0);
        a.emit(LOCK,0); a.emit(READ_DEC,i); int jnz=a.emit(JNZ,0);
        a.emit(CAPTURE_LIST,0); a.emit(FILL,i); a.emit(SET_DEC,i); a.emit(MAYBE_COMPLETE,0);
        int unlockAt=a.here(); a.emit(UNLOCK,0);
        int after=a.here();
        a.patch(jz,after); a.patch(jnz,unlockAt);
    }
    static void setItemInto(Asm a,int i,int v){
        a.emit(LOCK,0); a.emit(SETITEM,(i<<8)|v); a.emit(SET_DEC,i); a.emit(MAYBE_COMPLETE,0); a.emit(UNLOCK,0);
    }
    /** 逐格 ensureSlot:publishFirst=先標 decoded 再寫值(錯誤發佈順序);noLock=mask 用鎖外 read-modify-write。 */
    static void ensureSlotVariant(Asm a,int i,boolean publishFirst,boolean noLock){
        a.emit(READ_PENDING,0); int jz=a.emit(JZ,0);
        if(!noLock) a.emit(LOCK,0);
        a.emit(READ_DEC,i); int jnz=a.emit(JNZ,0);
        a.emit(CAPTURE_LIST,0);
        if(publishFirst){
            if(noLock){ a.emit(READ_MASK,0); a.emit(OR_MASK_WRITE,i); } else a.emit(SET_DEC,i);
            a.emit(FILL,i);
        } else {
            a.emit(FILL,i);
            if(noLock){ a.emit(READ_MASK,0); a.emit(OR_MASK_WRITE,i); } else a.emit(SET_DEC,i);
        }
        a.emit(MAYBE_COMPLETE,0);
        int unlockAt=a.here(); if(!noLock) a.emit(UNLOCK,0);
        int after=a.here();
        a.patch(jz,after); a.patch(jnz,unlockAt);
    }
    static int[] perSlotOne(int i,boolean publishFirst,boolean noLock){
        Asm a=new Asm(); ensureSlotVariant(a,i,publishFirst,noLock); return a.done(); }
    /** 逐格讀者:自旋等 slot i 的 decoded bit,一亮就(不持鎖)讀那一格。 */
    static int[] slotReader(int i){
        Asm a=new Asm(); int top=a.here();
        a.emit(READ_DEC,i); int jz=a.emit(JZ,top); a.emit(CHECK_SLOT,i);
        a.patch(jz,top); return a.done(); }

    static int[] perSlotT1(int slot,int setSlot,int val){
        Asm a=new Asm(); ensureSlotInto(a,slot); if(setSlot>=0) setItemInto(a,setSlot,val); return a.done();
    }
    /** 逐格存檔 A:先把未解碼的補齊(collapse)再照 vanilla 編碼。 */
    static int[] saveCollapse(){
        Asm a=new Asm();
        a.emit(LOCK,0); a.emit(READ_PENDING,0); int jz=a.emit(JZ,0);
        a.emit(CAPTURE_LIST,0); a.emit(FILL_UNDECODED,0); a.emit(SET_RAWID,RAW_NONE); a.emit(SET_PENDING,0);
        int enc=a.here(); a.emit(SAVE_ENCODE,0); a.emit(UNLOCK,0);
        a.patch(jz,enc); return a.done();
    }
    /** 逐格存檔 B:merge(已解碼用 items、未解碼用 raw)。emptyKey=用「空格」當未解碼判準(偷懶寫法)。 */
    static int[] saveMerge(boolean emptyKey){
        Asm a=new Asm(); a.emit(LOCK,0); a.emit(emptyKey?SAVE_MERGE_EMPTYKEY:SAVE_MERGE,0); a.emit(UNLOCK,0);
        return a.done();
    }

    // ── 執行一步 ──
    static St step(St in,int t,int[][] progs){
        int[] p=progs[t]; int pc=in.pc[t];
        if(pc*2>=p.length) return null;
        int op=p[pc*2], arg=p[pc*2+1];
        if(op==END) return null;
        if(op==LOCK && in.owner!=-1 && in.owner!=t) return null;      // 阻塞
        int[] logicalBefore=in.logical();
        St s=in.copy(); int[] raw=rawOf(s.rawId);
        switch(op){
            case LOCK -> { s.owner=t; s.depth++; }
            case UNLOCK -> { if(--s.depth==0) s.owner=-1; }
            case READ_PENDING -> s.loc[t]=s.pending?1:0;
            case READ_DEC -> s.loc[t]=(s.decoded>>>arg)&1;
            case JZ -> { if(s.loc[t]==0){ s.pc[t]=arg; return s; } }
            case JNZ -> { if(s.loc[t]!=0){ s.pc[t]=arg; return s; } }
            case JMP -> { s.pc[t]=arg; return s; }
            case CAPTURE_LIST -> s.cap[t]=s.listGen;
            case FILL -> { if(raw!=null && raw[arg]!=0) s.lists[s.cap[t]][arg]=raw[arg]; }
            case FILL_UNDECODED -> { if(raw!=null) for(int i=0;i<N;i++)
                    if(((s.decoded>>>i)&1)==0 && raw[i]!=0) s.lists[s.cap[t]][i]=raw[i];
                s.decoded=ALL; }
            case SET_DEC -> s.decoded|=(1<<arg);
            case READ_MASK -> s.loc[t]=s.decoded;
            case OR_MASK_WRITE -> s.decoded=s.loc[t]|(1<<arg);
            case CHECK_SLOT -> { if(s.live()[arg]!=RAW_A[arg])
                    s.violation="per-slot 讀者看到 decoded bit="+arg+" 卻讀到 "+s.live()[arg]+"(應為 "+RAW_A[arg]+")"; }
            case SET_DECODED -> s.decoded=arg;
            case MAYBE_COMPLETE -> { if(s.decoded==ALL){ s.pending=false; s.rawId=RAW_NONE; } }
            case SETITEM -> { s.lists[s.listGen][arg>>>8]=arg&0xFF; }
            case SET_PENDING -> s.pending=(arg!=0);
            case SET_RAWID -> s.rawId=arg;
            case NEW_ITEMS -> { s.listGen=1; s.lists[1]=new int[N]; }
            case CHECK_COMPLETE -> {
                int[] live=s.live();
                for(int i=0;i<N;i++) if(live[i]!=RAW_A[i]){
                    s.violation="讀到 pending=false 卻是半填清單 "+Arrays.toString(live); break; }
            }
            case SAVE_RAW -> s.saved= raw==null? new int[N] : raw.clone();
            case SAVE_ENCODE -> s.saved=s.live().clone();
            case SAVE_MERGE -> { int[] o=new int[N];
                for(int i=0;i<N;i++) o[i]=((s.decoded>>>i)&1)!=0 ? s.live()[i] : (raw==null?0:raw[i]);
                s.saved=o; }
            case SAVE_MERGE_EMPTYKEY -> { int[] o=new int[N];
                for(int i=0;i<N;i++) o[i]=s.live()[i]!=0 ? s.live()[i] : (raw==null?0:raw[i]);
                s.saved=o; }
            default -> throw new IllegalStateException("op "+op);
        }
        // 存檔的 linearization point:寫出的內容必須等於「那一瞬間的邏輯內容」
        if(s.saved!=null && in.saved==null && !Arrays.equals(s.saved,logicalBefore) && s.violation==null)
            s.violation="存檔寫出 "+Arrays.toString(s.saved)+" != 當下邏輯內容 "+Arrays.toString(logicalBefore);
        s.pc[t]=pc+1;
        return s;
    }

    interface Prop { String check(St s); }
    record Result(long states,long trans,long ends,long deadlocks,String ce,long ms){}

    static Result explore(St init,int[][] progs,Prop prop){
        long t0=System.nanoTime();
        Deque<St> stack=new ArrayDeque<>();
        Map<String,String> seen=new HashMap<>();
        stack.push(init); seen.put(init.key(),"");
        long trans=0, ends=0, dead=0; String ce=null;
        while(!stack.isEmpty()){
            St s=stack.pop(); String path=seen.get(s.key());
            if(s.violation!=null && ce==null) ce=path+"⇒ "+s.violation;
            boolean any=false;
            for(int t=0;t<2;t++){
                St n=step(s,t,progs); if(n==null) continue;
                any=true; trans++;
                String k=n.key();
                if(!seen.containsKey(k)){ seen.put(k,path+"T"+(t+1)+" "); stack.push(n); }
            }
            if(!any){
                ends++;
                if(s.owner!=-1) dead++;
                String bad=s.violation!=null?s.violation:prop.check(s);
                if(bad!=null && ce==null) ce=path+"⇒ "+bad;
            }
        }
        return new Result(seen.size(),trans,ends,dead,ce,(System.nanoTime()-t0)/1_000_000);
    }

    static St fresh(){ St s=new St(); s.pending=true; s.rawId=RAW_IDA; s.decoded=0; return s; }

    static long totalStates=0, totalMs=0;
    static void run(String name,int[][] progs,Prop prop,boolean expectViolation){
        Result r=explore(fresh(),progs,prop);
        totalStates+=r.states(); totalMs+=r.ms();
        boolean found=r.ce()!=null;
        String tag=found?"❌ 找到反例":"✅ 全通過";
        String ok=(found==expectViolation)?"如預期":"!!! 與預期不符 !!!";
        System.out.printf("  %-46s states=%-6d trans=%-6d 終局=%-3d 死結=%-3d %3dms  %s(%s)%n",
                name,r.states(),r.trans(),r.ends(),r.deadlocks(),r.ms(),tag,ok);
        if(found) System.out.println("      反例交錯: "+r.ce());
    }

    static void replay(int[][] progs,String path){
        St s=fresh(); System.out.println("  重放 "+path);
        for(String tok:path.trim().split(" ")){
            int t=tok.equals("T1")?0:1;
            int pc=s.pc[t]; int op=progs[t][pc*2], arg=progs[t][pc*2+1];
            St n=step(s,t,progs);
            System.out.printf("   %s op=%-16s live=%s decoded=%d pending=%s raw=%d owner=%d saved=%s%s%n",
                tok, OPN[op]+(arg!=0?("("+arg+")"):""), Arrays.toString(n.live()), n.decoded, n.pending,
                n.rawId, n.owner, n.saved==null?"-":Arrays.toString(n.saved),
                n.violation!=null?("  <<< "+n.violation):"");
            s=n;
        }
    }
    static final String[] OPN=new String[30];
    static { OPN[0]="END";OPN[1]="LOCK";OPN[2]="UNLOCK";OPN[3]="READ_PENDING";OPN[4]="JZ";OPN[5]="JNZ";
        OPN[6]="FILL";OPN[7]="SET_PENDING";OPN[8]="SET_RAWID";OPN[9]="CHECK_COMPLETE";OPN[10]="SAVE_RAW";
        OPN[11]="SAVE_ENCODE";OPN[12]="NEW_ITEMS";OPN[13]="SET_DECODED";OPN[16]="JMP";OPN[17]="READ_DEC";
        OPN[18]="SAVE_MERGE";OPN[21]="CAPTURE_LIST";OPN[22]="SET_DEC";OPN[23]="MAYBE_COMPLETE";OPN[24]="SETITEM";
        OPN[25]="SAVE_MERGE_EMPTY";OPN[26]="FILL_UNDECODED";OPN[27]="READ_MASK";OPN[28]="OR_MASK_WRITE";OPN[29]="CHECK_SLOT"; }

    public static void main(String[] a){
        if(a.length>0 && a[0].equals("debug")){
            setSize(3);
            Asm t1=new Asm();
            for(int i=0;i<saveCollapse().length-2;i+=2) t1.emit(saveCollapse()[i],saveCollapse()[i+1]);
            ensureSlotVariant(t1,0,false,false);
            Asm t2=new Asm(); ensureSlotVariant(t2,0,false,false);
            int off=t2.here(); int[] rl=reload(true,true);
            for(int i=0;i<rl.length-2;i+=2){ int op=rl[i],arg=rl[i+1]; if(op==JZ||op==JNZ||op==JMP) arg+=off; t2.emit(op,arg); }
            int[][] progs={t1.done(),t2.done()};
            Result r=explore(fresh(),progs,s2->null);
            System.out.println("反例: "+r.ce());
            if(r.ce()!=null) replay(progs, r.ce().substring(0, r.ce().indexOf("⇒")));
            return;
        }
        // 性質:最終邏輯內容 == 期望值
        Prop finalIsA = s -> Arrays.equals(s.logical(),RAW_A)?null:"最終邏輯內容 "+Arrays.toString(s.logical())+" != "+Arrays.toString(RAW_A);
        Prop finalIsB = s -> Arrays.equals(s.logical(),RAW_B)?null:"重新載入後邏輯內容 "+Arrays.toString(s.logical())+" != "+Arrays.toString(RAW_B)+"(物品復活或遺失)";
        Prop none = s -> null;

        setSize(3);
        System.out.println("=== A. 現行整箱設計:能不能重新抓到兩個已知的真 bug(N=3, 2 執行緒)===");
        run("A1 現行 ensure × guard 讀者",            new int[][]{ensureWhole(true),  reader()},        finalIsA,false);
        run("A1 舊版 ensure(先翻旗標)× guard 讀者",   new int[][]{ensureWhole(false), reader()},        finalIsA,true);
        run("A1 現行 ensure × 現行存檔(持鎖)",        new int[][]{ensureWhole(true),  saveWhole(true)}, none,    false);
        run("A1 舊版 ensure × 舊版存檔(不持鎖)",      new int[][]{ensureWhole(false), saveWhole(false)},none,    true);
        run("A2 ensure × leaf reload(有 clear guard)",new int[][]{ensureWhole(true),  reload(true,true)}, finalIsB,false);
        run("A2 ensure × leaf reload(無 guard)",      new int[][]{ensureWhole(true),  reload(false,true)},finalIsB,true);

        System.out.println();
        System.out.println("=== B. 逐格延遲解碼(提案設計)===");
        run("B1 逐格 × 存檔 collapse(先補齊再編碼)",   new int[][]{perSlotT1(0,1,7),  saveCollapse()},   none,false);
        run("B2 逐格 × 存檔 merge(mask 判準)",        new int[][]{perSlotT1(0,1,7),  saveMerge(false)}, none,false);
        run("B3 逐格 × 存檔 merge(『空格=未解碼』判準)",new int[][]{perSlotT1(0,0,0),  saveMerge(true)},  none,true);
        run("B4 逐格 × reload 忘了歸零 decodedMask",   new int[][]{perSlotT1(0,-1,0), reload(true,false)},finalIsB,true);
        run("B5 逐格 × reload(guard + 歸零 mask)",    new int[][]{perSlotT1(0,-1,0), reload(true,true)}, finalIsB,false);
        run("B6 逐格 × 只認 pending 的舊讀者(保守、永遠等整箱)", new int[][]{perSlotT1(0,-1,0), reader()},   none,false);
        run("B7 逐格 publish-first(先標 mask 再寫值)× 逐格讀者", new int[][]{perSlotOne(0,true,false), slotReader(0)}, none,true);
        run("B8 逐格 fill-first(寫完值才標 mask)× 逐格讀者",     new int[][]{perSlotOne(0,false,false),slotReader(0)}, none,false);
        run("B9 兩執行緒各解一格、mask 在鎖外 |=(lost update)",  new int[][]{perSlotOne(0,false,true), perSlotOne(1,false,true)},
                s2 -> (s2.decoded&3)==3?null:"decoded mask 遺失位元(lost update): "+Integer.toBinaryString(s2.decoded), true);
        run("B10 兩執行緒各解一格、mask 在鎖內(現行紀律)",       new int[][]{perSlotOne(0,false,false),perSlotOne(1,false,false)},
                s2 -> null, false);

        System.out.println();
        System.out.println("=== C. 規模:狀態數 vs 格數(逐格 ensureSlot(0..n-1) × 存檔 collapse)===");
        for(int n : new int[]{2,3,4,6,9,12,16,20,27}){
            setSize(n);
            Asm t1=new Asm(); for(int i=0;i<n;i++) ensureSlotInto(t1,i);   // 逐格解完整箱
            long t0=System.nanoTime();
            Result r=explore(fresh(),new int[][]{t1.done(),saveCollapse()},none);
            System.out.printf("  N=%-3d states=%-9d trans=%-10d %5dms%s%n",
                    n,r.states(),r.trans(),(System.nanoTime()-t0)/1_000_000, r.ce()==null?"":"  ❌ "+r.ce());
        }
        System.out.println();
        System.out.println("=== D. 對照:同樣逐格解碼但『不持鎖』(mask 用鎖外 RMW)的狀態空間 ===");
        for(int n : new int[]{2,3,4,5,6,7}){
            setSize(n);
            Asm t1=new Asm(); for(int i=0;i<n;i++) ensureSlotVariant(t1,i,false,true);
            Asm t2=new Asm(); for(int i=n-1;i>=0;i--) ensureSlotVariant(t2,i,false,true);
            long t0=System.nanoTime();
            Result r=explore(fresh(),new int[][]{t1.done(),t2.done()},
                    s2 -> s2.decoded==ALL?null:"mask 遺失位元 "+Integer.toBinaryString(s2.decoded));
            System.out.printf("  N=%-3d states=%-9d trans=%-10d %5dms%s%n",
                    n,r.states(),r.trans(),(System.nanoTime()-t0)/1_000_000, r.ce()==null?"":"  ❌ 有反例");
        }
        System.out.println();
        System.out.println("=== E. 『所有操作序列』:從菜單枚舉兩條執行緒的程式,每一對再窮舉交錯 ===");
        for(int n : new int[]{3,4}){
          for(int L : new int[]{1,2,3}){
            setSize(n);
            // 菜單:逐格解 slot0 / 逐格解 slot1 / 覆寫 slot0 / 覆寫 slot1 / 存檔(collapse)/ 重新載入
            List<Supplier<int[]>> menu = new ArrayList<>();
            menu.add(()->{Asm x=new Asm(); ensureSlotVariant(x,0,false,false); return x.done();});
            menu.add(()->{Asm x=new Asm(); ensureSlotVariant(x,1,false,false); return x.done();});
            menu.add(()->{Asm x=new Asm(); setItemInto(x,0,7); return x.done();});
            menu.add(()->{Asm x=new Asm(); setItemInto(x,1,7); return x.done();});
            menu.add(LazyModelCheck3::saveCollapse);
            menu.add(()->reload(true,true));
            List<int[]> programs=new ArrayList<>();
            int m=menu.size(); int combos=(int)Math.pow(m,L);
            for(int c=0;c<combos;c++){
                Asm x=new Asm(); int cc=c;
                for(int k=0;k<L;k++){ int idx=cc%m; cc/=m;
                    int[] piece=menu.get(idx).get();
                    int base=x.here();                    // ← 修正:基準要在迴圈外先取,否則每 emit 一條就漂移
                    for(int i=0;i<piece.length-2;i+=2){   // 去掉該段的 END,平移跳躍
                        int op=piece[i],arg=piece[i+1];
                        if(op==JZ||op==JNZ||op==JMP) arg+=base;
                        x.emit(op,arg);
                    }
                }
                programs.add(x.done());
            }
            String[] names={"ensureSlot0","ensureSlot1","setItem0","setItem1","saveCollapse","reload"};
            long t0=System.nanoTime(); long tot=0, pairs=0; String ce=null; int badPairs=0;
            Set<String> shapes=new LinkedHashSet<>();
            for(int i1=0;i1<programs.size();i1++) for(int i2=0;i2<programs.size();i2++){
                Result r=explore(fresh(),new int[][]{programs.get(i1),programs.get(i2)},s2->null);
                tot+=r.states(); pairs++;
                if(r.ce()!=null){ badPairs++;
                    StringBuilder sb=new StringBuilder("T1=");
                    int c1=i1; for(int k=0;k<L;k++){ sb.append(names[c1%m]).append(k<L-1?"+":""); c1/=m; }
                    sb.append("  T2="); int c2=i2; for(int k=0;k<L;k++){ sb.append(names[c2%m]).append(k<L-1?"+":""); c2/=m; }
                    shapes.add(sb.toString());
                    if(ce==null) ce=sb+"  ⇒ "+r.ce();
                }
            }
            if(badPairs>0){ System.out.println("      反例配對數="+badPairs+"/"+pairs+";形態:");
                int shown=0; for(String sh:shapes){ System.out.println("        "+sh); if(++shown>=8) {System.out.println("        …共 "+shapes.size()+" 種形態"); break;} } }
            System.out.printf("  N=%-2d 每緒 %d 個操作 → 程式 %d 支、配對 %d 組、狀態合計 %,d、%,d ms%s%n",
                n,L,programs.size(),pairs,tot,(System.nanoTime()-t0)/1_000_000, ce==null?"  ✅ 無反例":"  ❌ "+ce);
          }
        }
        System.out.println();
        System.out.printf("A+B 全部合計:狀態 %d、耗時 %d ms%n",totalStates,totalMs);
    }
}
