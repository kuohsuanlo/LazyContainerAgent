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

    /**
     * 側車被丟掉:差額被永久墊高、整個視窗都沒有回補過 ⟹ 必須大聲告警,而且要就地降級。
     *
     * <p>2026-09-09 紅綠驗證台的 dropSideCar 回合實測:故意吞掉 565 個側車,舊判準一行都沒印。
     * 原因是舊判準要求「這一輪沒有再掛新的側車」而且差額 ≥ 4096——前者在正式站幾乎不成立
     * (自動存檔是連續的),後者比整起 s3 事故的規模(169 個容器)還大。判準改成看差額的低水位。</p>
     */
    @Test
    public void sideCarDroppedRaisesAlarm() {
        reset();
        String err = runChecks(6, 100_000L, 0L);
        assertTrue(err.contains("BAD PASSTHROUGH"), "側車全被丟掉卻沒告警:" + err);
        assertTrue(err.contains("從來沒有回補過"), "告警沒講出判準是低水位:" + err);
        assertTrue(err.contains("SAFE MODE"), "告警沒有就地降級:" + err);
        assertFalse(LazyContainerRuntime.passthrough(), "告警之後直寫必須已經關掉");
    }

    /** 只掉一點點也要抓到:s3 那次總共才 169 個容器,門檻不能比事故還大。 */
    @Test
    public void smallButPermanentDeficitAlarms() {
        reset();
        String err = runChecks(6, 1_000L, 900L);      // 每輪掛 1000 寫出 900,差額一路墊高
        assertTrue(err.contains("BAD PASSTHROUGH"), "永久性的小額落後也必須告警:" + err);
    }

    /** 正常運作:每輪掛多少就寫出多少,差額恆為 0 ⟹ 不得告警。 */
    @Test
    public void normalIoLagIsSilent() {
        reset();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            all.append(runChecks(1, 5000L, 5000L));
        }
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "正常落後不該告警:" + all);
    }

    /**
     * 爆量存檔:差額在視窗內上上下下,但**回補過** ⟹ 不得告警。
     *
     * <p>這是新判準的核心:IO 落後會回補,所以差額會週期性掉回接近 0;側車真的被丟掉時,
     * 低水位會被永久墊高。判準只看視窗內的最低點,不需要「安靜的那一刻」。</p>
     */
    @Test
    public void bulkSaveBurstIsSilent() {
        reset();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            all.append(runChecks(1, 20_000L, 0L));       // 一陣爆量,IO 還沒跟上
            all.append(runChecks(1, 0L, 20_000L));       // 隨即追平
        }
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "會回補的爆量落後不該告警:" + all);
    }

    /** 一次性的落後尖峰(之後追上)不得告警。 */
    @Test
    public void transientSpikeIsSilent() {
        reset();
        StringBuilder all = new StringBuilder();
        all.append(runChecks(1, 50_000L, 10_000L));   // 尖峰:差 40000
        all.append(runChecks(1, 0L, 39_000L));        // 追上大半
        all.append(runChecks(1, 0L, 1_000L));         // 追平
        all.append(runChecks(3, 0L, 0L));             // 之後幾輪維持追平
        assertFalse(all.toString().contains("BAD PASSTHROUGH"), "一次性尖峰不該告警:" + all);
    }
}
