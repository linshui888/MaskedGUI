/*
   Copyright 2023-2023 Huynh Tien

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package me.hsgamer.bettergui.maskedgui.mask;

import me.hsgamer.bettergui.BetterGUI;
import me.hsgamer.bettergui.builder.ButtonBuilder;
import me.hsgamer.bettergui.builder.RequirementBuilder;
import me.hsgamer.bettergui.maskedgui.builder.MaskBuilder;
import me.hsgamer.bettergui.maskedgui.util.MultiSlotUtil;
import me.hsgamer.bettergui.requirement.type.ConditionRequirement;
import me.hsgamer.bettergui.util.MapUtil;
import me.hsgamer.hscore.bukkit.scheduler.Scheduler;
import me.hsgamer.hscore.bukkit.scheduler.Task;
import me.hsgamer.hscore.common.CollectionUtils;
import me.hsgamer.hscore.minecraft.gui.GUIProperties;
import me.hsgamer.hscore.minecraft.gui.button.Button;
import me.hsgamer.hscore.minecraft.gui.mask.impl.ButtonPaginatedMask;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class ValueListMask<T> extends WrappedPaginatedMask<ButtonPaginatedMask> {
    private final Pattern shortcutPattern;
    private final Map<T, ValueEntry<T>> valueEntryMap = new ConcurrentHashMap<>();
    private final Map<UUID, ValueListCache> playerListCacheMap = new ConcurrentHashMap<>();
    private final Function<Runnable, Task> scheduler;
    protected long valueUpdateTicks = 20L;
    protected long viewerUpdateMillis = 50L;
    private Map<String, Object> templateButton = Collections.emptyMap();
    private List<String> viewerConditionTemplate = Collections.emptyList();
    private Task updateTask;

    protected ValueListMask(Function<Runnable, Task> scheduler, MaskBuilder.Input input) {
        super(input);
        this.scheduler = scheduler;
        shortcutPattern = Pattern.compile("\\{" + Pattern.quote(getShortcutPatternPrefix()) + "(_([^{}]+))?}");
    }

    protected ValueListMask(MaskBuilder.Input input) {
        super(input);
        this.scheduler = runnable -> Scheduler.CURRENT.runTaskTimer(BetterGUI.getInstance(), runnable, 0L, valueUpdateTicks, true);
        shortcutPattern = Pattern.compile("\\{" + Pattern.quote(getShortcutPatternPrefix()) + "(_([^{}]+))?}");
    }

    protected abstract String getShortcutPatternPrefix();

    protected abstract Stream<T> getValueStream();

    protected abstract String getShortcutReplacement(String argument, T value);

    protected abstract String getValueIndicator();

    protected abstract String getValueAsString(T value);

    protected abstract boolean isValueActivated(T value);

    protected abstract boolean canViewValue(UUID uuid, T value);

    private String replaceShortcut(String string, T value) {
        Matcher matcher = shortcutPattern.matcher(string);
        while (matcher.find()) {
            String variable = matcher.group(2);
            String replacement = getShortcutReplacement(variable == null ? "" : variable, value);
            string = string.replace(matcher.group(), replacement);
        }
        return string;
    }

    private Object replaceShortcut(Object obj, T value) {
        if (obj instanceof String) {
            return replaceShortcut((String) obj, value);
        } else if (obj instanceof Collection) {
            List<Object> replaceList = new ArrayList<>();
            ((Collection<?>) obj).forEach(o -> replaceList.add(replaceShortcut(o, value)));
            return replaceList;
        } else if (obj instanceof Map) {
            // noinspection unchecked, rawtypes
            ((Map) obj).replaceAll((k, v) -> replaceShortcut(v, value));
        }
        return obj;
    }

    private Map<String, Object> replaceShortcut(Map<String, Object> map, T value) {
        Map<String, Object> newMap = new LinkedHashMap<>();
        map.forEach((k, v) -> newMap.put(k, replaceShortcut(v, value)));
        return newMap;
    }

    private List<String> replaceShortcut(List<String> list, T value) {
        List<String> newList = new ArrayList<>();
        list.forEach(s -> newList.add(replaceShortcut(s, value)));
        return newList;
    }

    private boolean canView(UUID uuid, ValueEntry<T> valueEntry) {
        return valueEntry.activated.get() && canViewValue(uuid, valueEntry.value) && valueEntry.viewerCondition.test(uuid);
    }

    private ValueEntry<T> newValueEntry(T value) {
        Map<String, Object> replacedButtonSettings = replaceShortcut(templateButton, value);
        Button button = ButtonBuilder.INSTANCE.build(new ButtonBuilder.Input(getMenu(), String.join("_", getName(), getValueIndicator(), getValueAsString(value), "button"), replacedButtonSettings))
                .map(Button.class::cast)
                .orElse(Button.EMPTY);
        button.init();

        List<String> replacedViewerConditions = replaceShortcut(viewerConditionTemplate, value);
        ConditionRequirement viewerCondition = new ConditionRequirement(new RequirementBuilder.Input(getMenu(), "condition", String.join("_", getName(), getValueIndicator(), getValueAsString(value), "condition"), replacedViewerConditions));
        return new ValueEntry<>(value, button, uuid1 -> viewerCondition.check(uuid1).isSuccess);
    }

    private List<Button> getPlayerButtons(UUID uuid) {
        return playerListCacheMap.compute(uuid, (u, cache) -> {
            long now = System.currentTimeMillis();
            if (cache != null) {
                long remaining = cache.lastUpdate - now;
                if (remaining > viewerUpdateMillis) {
                    return cache;
                }
            }
            return new ValueListCache(
                    now,
                    getValueStream()
                            .map(valueEntryMap::get)
                            .filter(Objects::nonNull)
                            .filter(entry -> canView(uuid, entry))
                            .map(entry -> entry.button)
                            .collect(Collectors.toList())
            );
        }).buttonList;
    }

    @Override
    protected ButtonPaginatedMask createPaginatedMask(Map<String, Object> section) {
        templateButton = Optional.ofNullable(MapUtil.getIfFound(section, "template", "button"))
                .flatMap(MapUtil::castOptionalStringObjectMap)
                .orElse(Collections.emptyMap());
        viewerConditionTemplate = Optional.ofNullable(MapUtil.getIfFound(section, "viewer-condition"))
                .map(CollectionUtils::createStringListFromObject)
                .orElse(Collections.emptyList());
        viewerUpdateMillis = Optional.ofNullable(MapUtil.getIfFound(section, "viewer-update-ticks", "viewer-update"))
                .map(String::valueOf)
                .map(Long::parseLong)
                .map(ticks -> Math.max(ticks, 1) * GUIProperties.getMillisPerTick())
                .map(millis -> Math.max(millis, 1L))
                .orElse(50L);
        valueUpdateTicks = Optional.ofNullable(MapUtil.getIfFound(section, "value-update-ticks", "value-update"))
                .map(String::valueOf)
                .map(Long::parseLong)
                .orElse(20L);
        return new ButtonPaginatedMask(getName(), MultiSlotUtil.getSlots(section)) {
            @Override
            public @NotNull List<@NotNull Button> getButtons(@NotNull UUID uuid) {
                return getPlayerButtons(uuid);
            }
        };
    }

    @Override
    public void init() {
        super.init();
        updateTask = scheduler.apply(this::updateValueList);
    }

    @Override
    public void stop() {
        super.stop();
        if (updateTask != null) {
            updateTask.cancel();
        }
        valueEntryMap.values().forEach(playerEntry -> playerEntry.button.stop());
        valueEntryMap.clear();
    }

    private void updateValueList() {
        getValueStream().forEach(value -> valueEntryMap.compute(value, (currentValue, currentEntry) -> {
            if (currentEntry == null) {
                currentEntry = newValueEntry(value);
            }
            currentEntry.activated.lazySet(isValueActivated(value));
            return currentEntry;
        }));
    }

    private static class ValueEntry<T> {
        final T value;
        final Button button;
        final Predicate<UUID> viewerCondition;
        final AtomicBoolean activated = new AtomicBoolean();

        private ValueEntry(T value, Button button, Predicate<UUID> viewerCondition) {
            this.value = value;
            this.button = button;
            this.viewerCondition = viewerCondition;
        }
    }

    private static class ValueListCache {
        final long lastUpdate;
        final List<Button> buttonList;

        private ValueListCache(long lastUpdate, List<Button> buttonList) {
            this.lastUpdate = lastUpdate;
            this.buttonList = buttonList;
        }
    }
}