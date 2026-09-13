package com.dddgn.alice.task.craft;

import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * **菜单内省的只读诊断**（阶段 3-A / S1-3 诊断件，D-192）。
 *
 * <p>为什么需要它：2026-09-13 出现一个**用推理解决不了**的矛盾 —— 方块实体的存档里明明写着
 * `numberOfUpgradeSlots: 1` + `upgradeInventory:[crafting_upgrade]`（存档写入时刻 17:13:43，**早于**探针 17:13:57），
 * 可是 bot 右键开出来的服务端菜单只有 **63 槽（27 存储 + 36 玩家）**：**连升级槽本身都没有**，
 * 自然也没有合成网格。⇒ 必须把"**菜单手里的包装器**"和"**方块实体手里的包装器**"两个事实**分别**打印出来对比。
 *
 * <p>**纪律**：① 只读（只调 getter / 读字段，绝不 setter）；② 全部反射 + 逐项 try/catch
 * （未知模组对象可能在**任何**地方抛）；③ 只认**接口/方法名形态**，不写死实现类名；
 * ④ 这是**诊断**，不是适配层 —— 生产路径不许依赖它（本类只被自检探针调用）。
 */
public final class CraftMenuIntrospection {

    private CraftMenuIntrospection() {
    }

    /** 逐项事实，`key=value` 形式（异常也会变成一行事实，而不是崩服务端）。 */
    public static List<String> facts(AbstractContainerMenu menu, ServerLevel level) {
        List<String> out = new ArrayList<>();
        if (menu == null) {
            return out;
        }
        Object menuWrapper = readField(menu, "storageWrapper");
        out.add("diag_menu_wrapper=" + describeWrapper(menuWrapper));
        Object be = readField(menu, "storageBlockEntity");
        if (be == null) {
            // 有些菜单不直接持有方块实体：退一步用 getBlockPosition() 去查
            Object pos = call(menu, "getBlockPosition");
            if (pos instanceof java.util.Optional<?> opt && opt.orElse(null) instanceof net.minecraft.core.BlockPos bp) {
                be = level.getBlockEntity(bp);
            }
        }
        out.add("diag_block_entity=" + (be == null ? "-" : be.getClass().getSimpleName()));
        Object beWrapper = be == null ? null : call(be, "getStorageWrapper");
        out.add("diag_be_wrapper=" + describeWrapper(beWrapper));
        // 菜单自己的容器表（上游 Core 用 upgradeContainers 存"升级页签容器"）
        Object containers = readField(menu, "upgradeContainers");
        out.add("diag_menu_upgradeContainers=" + (containers instanceof java.util.Map<?, ?> map
                ? map.size() + " " + map.keySet() : String.valueOf(containers)));
        return out;
    }

    /** 把一个"存储包装器"的关键计数读出来（**接口形态**，不认具体类名）。 */
    private static String describeWrapper(Object wrapper) {
        if (wrapper == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(wrapper.getClass().getSimpleName());
        Object upgradeHandler = call(wrapper, "getUpgradeHandler");
        sb.append(" upgradeHandler=").append(describeHandler(upgradeHandler));
        Object invHandler = call(wrapper, "getInventoryHandler");
        sb.append(" inventoryHandler=").append(describeHandler(invHandler));
        return sb.toString();
    }

    private static String describeHandler(Object handler) {
        if (handler == null) {
            return "-";
        }
        Object slots = call(handler, "getSlots");
        StringBuilder sb = new StringBuilder(handler.getClass().getSimpleName());
        sb.append("[slots=").append(slots instanceof Integer i ? i : "?").append(']');
        if (slots instanceof Integer count && count > 0 && count <= 16) {
            for (int i = 0; i < count; i++) {
                Object stack = callWith(handler, "getStackInSlot", new Class<?>[]{int.class}, i);
                // **编译期调用**而不是字符串反射：vanilla 的方法名（`ItemStack.getItem()`）在 Forge 生产环境里
                // 是 SRG 名 ⇒ 用 `"getItem"` 反射只会拿到 null（2026-09-13 实测 `0:null` 就是这个原因）。
                String id = "?";
                if (stack instanceof net.minecraft.world.item.ItemStack itemStack) {
                    id = itemStack.isEmpty() ? "empty"
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(itemStack.getItem()) + "x" + itemStack.getCount();
                }
                sb.append(' ').append(i).append(':').append(id);
            }
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        int at = s.lastIndexOf('.');
        return at < 0 ? s : s.substring(at + 1);
    }

    /** 向上遍历类层次找字段（模组字段多半在父类上）。找不到返回 null（**不抛**）。 */
    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                BotLog.warn("[Diag] 读字段 {} 失败：{}", name, t.toString());
                return null;
            }
        }
        return null;
    }

    private static Object call(Object target, String name) {
        return callWith(target, name, new Class<?>[0]);
    }

    private static Object callWith(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                BotLog.warn("[Diag] 调 {}.{} 失败：{}", target.getClass().getSimpleName(), name, t.toString());
                return null;
            }
        }
        return null;
    }
}
