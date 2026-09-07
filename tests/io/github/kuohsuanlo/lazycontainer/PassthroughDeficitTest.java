package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/**
 * 直寫對帳告警(rawEmit vs rawPassthrough)。
 *
 * <p>守的是換版最危險、且唯一「不會自己爆」的失效模式:三個 hook 都 armed、attachRaw 成功、
 * rawPassthrough 照加,但核心在存檔鏈中途重建了 CompoundTag,把側車丟掉 ⟹ 箱子存成空。
 * 那種情況下唯一的外顯訊號就是「掛上去的次數」追不上「真的寫出去的次數」。</p>
 *
 * <p>同時守另一半:正常運作時的短暫落後(存檔收集在 region 執行緒、真正寫出在 IO 執行緒)
 * <b>不可以</b>告警,否則值班的人會學會忽略它。</p>
 */
public class PassthroughDeficitTest {

    private static String runChecks(int times, long attach, long emit) {
        LazyContainerRuntime.rawPassthrough.add(attach);
        LazyContainerRuntime.rawEmit.add(emit);
        PrintStream old = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bo));
        try {
            for (int i = 0; i < times; i++) {
                LazyContainerRuntime.checkPassthroughDeficit();
            }
        } finally {
            System.setErr(old);
        }
        return bo.toString();
    }

    private static void reset() {
        LazyContainerRuntime.rawPassthrough.reset();
        LazyContainerRuntime.rawEmit.reset();
        LazyContainerRuntime.resetPassthroughDeficitStateForTest();
    }

    /** 側車被丟掉:已經沒有新的掛上去,寫出的數字卻還是追不上 ⟹ 必須大聲告警,而且要講得出怎麼止血。 */
    @Test
    public void sideCarDroppedRaisesAlarm() {
        reset();
        String err = runChecks(4, 100_000L, 0L);
        assertTrue(err.contains("BAD PASSTHROUGH"), "側車全被丟掉卻沒告警:" + err);
        assertTrue(err.contains("passthrough=false"), "告警沒告訴值班的人怎麼止血:" + err);
    }

    /** 正常的 IO 落後:差額存在但每輪都在回補 ⟹ 不得告警。 */
    @Test
    public void normalIoLagIsSilent() {
        reset();
        StringBuilder all = new StringBuilder();
        // 每輪掛上 5000、寫出 4900,落後量固定在小範圍且不斷被追上
        for (int i = 0; i < 6; i++) {
            all.append(runChecks(1, 5000L, 5000L));
        }
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "正常落後不該告警:" + all);
    }

    /**
     * 爆量存檔:落後量連著好幾輪往上長,但每一輪都還在掛新的側車 ⟹ 不得告警。
     * 這條是實測補上的——第一版判準只看「差額不降」,大批存檔當下就會誤報,
     * 而誤報的代價是叫值班的人去關掉直寫。會誤報的告警等於沒有告警。
     */
    @Test
    public void bulkSaveBurstIsSilent() {
        reset();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            all.append(runChecks(1, 20_000L, 0L));   // 每輪又掛 20000,IO 完全還沒跟上
        }
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "爆量存檔期間不該告警:" + all);
        all.append(runChecks(1, 0L, 120_000L));      // IO 追平
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "追平後更不該有告警:" + all);
    }

    /** 一次性的落後尖峰(之後追上)也不得告警——只有「已經靜止還追不上」才算真的丟了。 */
    @Test
    public void transientSpikeIsSilent() {
        reset();
        StringBuilder all = new StringBuilder();
        all.append(runChecks(1, 50_000L, 10_000L));   // 尖峰:差 40000
        all.append(runChecks(1, 0L, 39_000L));        // 追上大半
        all.append(runChecks(1, 0L, 1_000L));         // 追平
        all.append(runChecks(1, 0L, 0L));
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "一次性尖峰不該告警:" + all);
    }
}
