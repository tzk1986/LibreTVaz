import com.ibm.icu.text.Collator;
import com.ibm.icu.util.ULocale;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P0-1（审计 2026-11-11）pin_index.bin 生成器：
 * 与 PinIndex.kt 锚点区间法逐字一致的离线预生成（23 锚点表须与 PIN_ANCHORS 同步维护）。
 * 运行（android/ 目录下，jar 路径换成本机 gradle 缓存实际哈希）：
 *   java -cp "$HOME/.gradle/caches/modules-2/files-2.1/com.ibm.icu/icu4j/76.1/<hash>/icu4j-76.1.jar" \
 *        tools/PinTableGen.java
 * 输出 app/src/main/assets/pin_index.bin（27584 字节）；改锚点后必重跑，
 * PinTableParityTest 会全表逐字校验打包文件防漂移。
 */
public class PinTableGen {
    static final Object[][] ANCHORS = {
        {'A', "阿"}, {'B', "八"}, {'C', "嚓"}, {'D', "大"}, {'E', "讹"}, {'F', "发"},
        {'G', "噶"}, {'H', "哈"}, {'J', "击"}, {'K', "喀"}, {'L', "垃"}, {'M', "妈"},
        {'N', "拿"}, {'O', "哦"}, {'P', "啪"}, {'Q', "期"}, {'R', "然"}, {'S', "撒"},
        {'T', "塌"}, {'W', "蛙"}, {'X', "昔"}, {'Y', "压"}, {'Z', "匝"},
    };

    public static void main(String[] args) throws Exception {
        Collator c = Collator.getInstance(ULocale.forLanguageTag("zh-Hans-CN"));
        c.setStrength(Collator.TERTIARY);
        byte[] table = new byte[27584];
        int i = 0;
        for (int[] range : new int[][]{{0x3400, 0x4DBF}, {0x4E00, 0x9FFF}}) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                String s = String.valueOf((char) cp);
                int res = 0;
                for (Object[] a : ANCHORS) {
                    if (c.compare(s, (String) a[1]) >= 0) res = Character.toLowerCase((Character) a[0]);
                    else break;
                }
                table[i++] = (byte) res;
            }
        }
        Path out = Path.of("app", "src", "main", "assets", "pin_index.bin");
        Files.createDirectories(out.getParent());
        Files.write(out, table);
        long hits = 0;
        for (byte b : table) if (b != 0) hits++;
        System.out.println("wrote " + out + " size=" + table.length + " hits=" + hits + " zero=" + (table.length - hits));
    }
}
