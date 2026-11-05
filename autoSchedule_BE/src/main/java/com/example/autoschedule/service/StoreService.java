package com.example.autoschedule.service;

import com.example.autoschedule.dto.StoreDto;
import com.example.autoschedule.entity.Part;
import com.example.autoschedule.entity.Store;
import com.example.autoschedule.entity.StoreDemand;
import com.example.autoschedule.entity.StoreHours;
import com.example.autoschedule.entity.Weekday;
import com.example.autoschedule.repository.ShiftRepository;
import com.example.autoschedule.repository.StoreRepository;
import com.example.autoschedule.repository.UserRepository;
import com.example.autoschedule.repository.WorkerAvailabilityRepository;
import com.example.autoschedule.repository.WorkerRepository;
import com.example.autoschedule.util.TimeCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 로그인한 사용자의 매장(사용자당 1개) 하나를 프론트의 Store 묶음으로 읽고 쓴다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private static final int DAYS = Weekday.values().length;
    private static final int DEFAULT_BREAK_MINUTES = 30;
    private static final int DEFAULT_MINIMUM_WAGE = 10320;
    /** 상시 근로자 수가 이 이상이면 5인 이상 사업장 */
    private static final int OVER_5_STAFF = 5;

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final WorkerAvailabilityRepository availabilityRepository;
    private final WorkerRepository workerRepository;
    private final ShiftRepository shiftRepository;

    /** 매장이 없는 사용자는 프론트 기본값으로 매장을 만들어 돌려준다. */
    @Transactional
    public StoreDto.Response get(Long userId) {
        return toResponse(ensureStore(userId));
    }

    @Transactional
    public Store ensureStore(Long userId) {
        return storeRepository.findByUserId(userId).orElseGet(() -> create(userId, defaultRequest()));
    }

    /** 매장이 없으면 만들고 있으면 수정한다. 파트·필요 인원·영업시간은 요청 내용으로 맞춘다. */
    @Transactional
    public StoreDto.Response save(Long userId, StoreDto.Request request) {
        Store store = storeRepository.findByUserId(userId).orElse(null);
        return toResponse(store == null ? create(userId, request) : update(store, request));
    }

    // ---- 쓰기 ----

    private Store create(Long userId, StoreDto.Request request) {
        Store store = new Store();
        store.setUser(userRepository.getReferenceById(userId));
        return update(store, request);
    }

    private Store update(Store store, StoreDto.Request r) {
        store.setName(r.name().trim());
        store.setBranch(r.branch() == null || r.branch().isBlank() ? null : r.branch().trim());
        store.setSizeMode(r.sizeMode() != null ? r.sizeMode()
                : r.size() == null ? Store.SizeMode.AUTO : Store.SizeMode.valueOf(r.size().name()));
        store.setScale(scaleOf(store));
        store.setMinimumWage(r.minimumWage());

        replaceHours(store, r);
        reconcileParts(store, r.parts());
        replaceDemand(store, r.demand() != null ? r.demand() : demandFromParts(r.parts()));

        return storeRepository.saveAndFlush(store);
    }

    /** 구성원 수가 바뀐 뒤 부른다. AUTO 면 규모를 다시 정한다. */
    @Transactional
    public void refreshScale(Store store) {
        store.setScale(scaleOf(store));
    }

    // shortcut: 상시 근로자 수를 등록 구성원 수로 본다(프론트 sizeOf 와 같음). 법 기준은 직전 1개월 평균 인원이다.
    private Store.Scale scaleOf(Store store) {
        Store.SizeMode mode = store.getSizeMode() == null ? Store.SizeMode.AUTO : store.getSizeMode();
        if (mode != Store.SizeMode.AUTO) return Store.Scale.valueOf(mode.name());
        long staff = store.getId() == null ? 0 : workerRepository.countByStoreId(store.getId());
        return staff >= OVER_5_STAFF ? Store.Scale.OVER_5 : Store.Scale.UNDER_5;
    }

    /** 프론트는 파트별 필요 인원만 보낸다. 파트 시간대마다 매일 그 인원이 필요하다고 본다(겹치는 파트는 더해진다). */
    private static List<StoreDto.DemandItem> demandFromParts(List<StoreDto.PartItem> parts) {
        return parts.stream()
                .map(p -> new StoreDto.DemandItem(p.start(), p.end(), p.required() == null ? 1 : p.required(), null))
                .toList();
    }

    /** 평일(월~금)과 주말(토·일) 영업시간을 요일별 행으로 풀어 저장한다. 공휴일은 프론트가 처리한다. */
    private void replaceHours(Store store, StoreDto.Request r) {
        LocalTime open = TimeCodec.start(r.open());
        LocalTime close = TimeCodec.end(open, r.close());
        LocalTime weekendOpen = TimeCodec.start(r.weekendOpen());
        LocalTime weekendClose = TimeCodec.end(weekendOpen, r.weekendClose());

        store.getHours().clear();
        for (Weekday day : Weekday.values()) {
            boolean weekend = day == Weekday.SAT || day == Weekday.SUN;
            StoreHours hours = new StoreHours();
            hours.setStore(store);
            hours.setDayOfWeek(day);
            hours.setOpenTime(weekend ? weekendOpen : open);
            hours.setCloseTime(weekend ? weekendClose : close);
            store.getHours().add(hours);
        }
    }

    /**
     * 요청의 파트를 기준으로 맞춘다: 내 매장에 있는 숫자 id 는 수정, 그 외(새 id·임시 id)는 추가, 요청에 없는 기존 파트는 삭제.
     * DB 의 FK 는 Hibernate 가 만든 것이라 ON DELETE 동작이 없다. 그래서 지우는 파트를 가리키던 근무는 파트 없는 근무로 바꾸고,
     * 구성원의 가능 파트 행은 지운 뒤에 파트를 지운다(프론트 mock 의 updateStore 와 같은 동작).
     */
    private void reconcileParts(Store store, List<StoreDto.PartItem> items) {
        Map<Long, Part> existing = store.getParts().stream().collect(Collectors.toMap(Part::getId, Function.identity()));
        Set<Part> keep = new HashSet<>();
        for (StoreDto.PartItem item : items) {
            Part part = existing.get(parseId(item.id()));
            if (part == null) {
                part = new Part();
                part.setStore(store);
                store.getParts().add(part);
            }
            LocalTime start = TimeCodec.start(item.start());
            part.setName(item.name().trim());
            part.setStartTime(start);
            part.setEndTime(TimeCodec.end(start, item.end()));
            part.setBreakMinutes(item.breakMinutes() == null ? DEFAULT_BREAK_MINUTES : item.breakMinutes());
            part.setRequiredCount(item.required() == null ? 1 : item.required());
            keep.add(part);
        }
        List<Long> removed = existing.values().stream().filter(p -> !keep.contains(p)).map(Part::getId).toList();
        if (!removed.isEmpty()) {
            shiftRepository.clearPart(removed);
            availabilityRepository.deleteByPartIdIn(removed);
        }
        store.getParts().removeIf(p -> !keep.contains(p));
    }

    /** days 가 없으면 매일, 있으면 그 요일마다 한 행씩 저장한다. */
    private void replaceDemand(Store store, List<StoreDto.DemandItem> items) {
        store.getDemand().clear();
        for (StoreDto.DemandItem item : items) {
            LocalTime start = TimeCodec.start(item.start());
            LocalTime end = TimeCodec.end(start, item.end());
            List<Integer> days = item.days() == null || item.days().isEmpty()
                    ? java.util.stream.IntStream.range(0, DAYS).boxed().toList()
                    : item.days().stream().distinct().sorted().toList();
            for (int day : days) {
                StoreDemand demand = new StoreDemand();
                demand.setStore(store);
                demand.setDayOfWeek(Weekday.values()[day]);
                demand.setStartTime(start);
                demand.setEndTime(end);
                demand.setRequiredCount(item.required());
                store.getDemand().add(demand);
            }
        }
    }

    /** 프론트 기본값(defaults.ts)과 같은 매장. */
    private static StoreDto.Request defaultRequest() {
        return new StoreDto.Request("내 매장", null, Store.SizeMode.AUTO, null, "08:00", "22:00", "08:00", "22:00",
                List.of(new StoreDto.PartItem(null, "오픈", null, "08:00", "12:00", DEFAULT_BREAK_MINUTES, 1),
                        new StoreDto.PartItem(null, "미들", null, "12:00", "17:00", DEFAULT_BREAK_MINUTES, 1),
                        new StoreDto.PartItem(null, "마감", null, "17:00", "22:00", DEFAULT_BREAK_MINUTES, 1)),
                null,
                DEFAULT_MINIMUM_WAGE);
    }

    // ---- 읽기 ----

    private StoreDto.Response toResponse(Store store) {
        Map<Weekday, StoreHours> hours = store.getHours().stream()
                .collect(Collectors.toMap(StoreHours::getDayOfWeek, Function.identity()));
        StoreHours weekday = hours.getOrDefault(Weekday.MON, hours.values().stream().findFirst().orElse(null));
        StoreHours weekend = hours.getOrDefault(Weekday.SAT, hours.getOrDefault(Weekday.SUN, weekday));

        List<Part> parts = store.getParts().stream().sorted(Comparator.comparing(Part::getStartTime)).toList();
        List<StoreDto.PartItem> partItems = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            partItems.add(new StoreDto.PartItem(String.valueOf(p.getId()), p.getName(), tone(i, parts.size()),
                    TimeCodec.formatStart(p.getStartTime()), TimeCodec.formatEnd(p.getStartTime(), p.getEndTime()),
                    p.getBreakMinutes(), p.getRequiredCount() == null ? 1 : p.getRequiredCount()));
        }

        return new StoreDto.Response(store.getId(), store.getName(), store.getBranch() == null ? "" : store.getBranch(),
                store.getSizeMode() == null ? Store.SizeMode.AUTO : store.getSizeMode(), store.getScale(),
                open(weekday), close(weekday), open(weekend), close(weekend),
                partItems, demandItems(store), store.getMinimumWage());
    }

    /** 같은 (시작, 종료, 인원)인 요일 행들을 days 로 묶는다. 7일 전부면 days 를 생략(매일)한다. */
    private List<StoreDto.DemandItem> demandItems(Store store) {
        List<StoreDemand> rows = store.getDemand().stream()
                .sorted(Comparator.comparing(StoreDemand::getStartTime)
                        .thenComparing(StoreDemand::getEndTime)
                        .thenComparing(StoreDemand::getRequiredCount)
                        .thenComparing(StoreDemand::getDayOfWeek))
                .toList();
        Map<String, List<StoreDemand>> grouped = new LinkedHashMap<>();
        for (StoreDemand row : rows) {
            grouped.computeIfAbsent(row.getStartTime() + "|" + row.getEndTime() + "|" + row.getRequiredCount(),
                    k -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream().map(group -> {
            StoreDemand first = group.get(0);
            List<Integer> days = group.stream().map(g -> g.getDayOfWeek().ordinal()).toList();
            return new StoreDto.DemandItem(TimeCodec.formatStart(first.getStartTime()),
                    TimeCodec.formatEnd(first.getStartTime(), first.getEndTime()),
                    first.getRequiredCount(), days.size() == DAYS ? null : days);
        }).toList();
    }

    // ---- 내부 ----

    /** shortcut: parts 에 tone 컬럼이 없어 시작 시각 순서로 오픈/미들/마감을 정한다. */
    private static String tone(int index, int size) {
        if (index == 0) {
            return "open";
        }
        return index == size - 1 ? "close" : "middle";
    }

    private static String open(StoreHours h) {
        return h == null ? "09:00" : TimeCodec.formatStart(h.getOpenTime());
    }

    private static String close(StoreHours h) {
        return h == null ? "22:00" : TimeCodec.formatEnd(h.getOpenTime(), h.getCloseTime());
    }

    /** 숫자가 아니면(새 파트의 임시 id 포함) null → 새 파트로 취급한다. */
    private static Long parseId(String id) {
        try {
            return id == null ? null : Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
