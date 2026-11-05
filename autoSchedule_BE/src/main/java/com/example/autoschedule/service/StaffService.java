package com.example.autoschedule.service;

import com.example.autoschedule.dto.StaffDto;
import com.example.autoschedule.entity.Part;
import com.example.autoschedule.entity.Store;
import com.example.autoschedule.entity.Weekday;
import com.example.autoschedule.entity.Worker;
import com.example.autoschedule.entity.WorkerAvailability;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.repository.ShiftRepository;
import com.example.autoschedule.repository.StoreRepository;
import com.example.autoschedule.repository.WorkerAvailabilityRepository;
import com.example.autoschedule.repository.WorkerRepository;
import com.example.autoschedule.util.TimeCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 로그인한 사용자의 매장에 속한 직원/알바생만 다룬다. 남의 구성원은 없는 것처럼 404 로 응답한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffService {

    private static final int DAYS = Weekday.values().length;
    private static final int MAX_WEEKLY_HOURS_OVER_5 = 52;
    private static final int HOURS_PER_WEEK = 24 * 7;

    private final StoreRepository storeRepository;
    private final WorkerRepository workerRepository;
    private final WorkerAvailabilityRepository availabilityRepository;
    private final StoreService storeService;
    private final ShiftRepository shiftRepository;

    /** 매장이 아직 없으면 빈 목록을 돌려준다. */
    public List<StaffDto.Response> list(Long userId) {
        Optional<Store> store = storeRepository.findByUserId(userId);
        if (store.isEmpty()) {
            return List.of();
        }
        List<Worker> workers = workerRepository.findByStoreIdOrderById(store.get().getId());
        if (workers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<WorkerAvailability>> rows = availabilityRepository
                .findByWorkerIdIn(workers.stream().map(Worker::getId).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.getWorker().getId()));
        return workers.stream().map(w -> toResponse(w, rows.getOrDefault(w.getId(), List.of()))).toList();
    }

    @Transactional
    public StaffDto.Response create(Long userId, StaffDto.Request request) {
        // 매장 설정을 열기 전에 직원부터 등록해도 되도록, 매장이 없으면 기본 매장을 만든다.
        Store store = storeService.ensureStore(userId);
        Worker worker = new Worker();
        worker.setStore(store);
        StaffDto.Response saved = write(store, worker, request);
        storeService.refreshScale(store);
        return saved;
    }

    @Transactional
    public StaffDto.Response update(Long userId, Long workerId, StaffDto.Request request) {
        Store store = myStore(userId);
        return write(store, owned(store, workerId), request);
    }

    /** worker_availability 는 cascade 로 함께 지워지고, 근무·연차는 먼저 지운다(프론트 mock 과 같음). */
    @Transactional
    public void delete(Long userId, Long workerId) {
        Store store = myStore(userId);
        Worker worker = owned(store, workerId);
        shiftRepository.deleteByWorkerId(worker.getId());
        workerRepository.delete(worker);
        workerRepository.flush();
        storeService.refreshScale(store);
    }

    // ---- 내부 ----

    private Store myStore(Long userId) {
        return storeRepository.findByUserId(userId).orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
    }

    private Worker owned(Store store, Long workerId) {
        return workerRepository.findByIdAndStoreId(workerId, store.getId())
                .orElseThrow(() -> ApiException.notFound("구성원을 찾을 수 없습니다."));
    }

    private StaffDto.Response write(Store store, Worker worker, StaffDto.Request r) {
        boolean partTime = r.employmentType() == Worker.EmploymentType.PART_TIME;

        worker.setName(r.name().trim());
        worker.setEmploymentType(r.employmentType());
        worker.setTitle(blankToNull(r.title()));
        worker.setHourlyWage(r.hourlyWage());
        worker.setPhone(blankToNull(r.phone()));
        worker.setMinor(Boolean.TRUE.equals(r.isMinor()));
        // 직원은 계약 주 근로시간, 알바생은 희망 주간 근무시간만 쓴다.
        worker.setContractWeeklyHours(partTime ? null : r.contractWeeklyHours());
        Integer min = null;
        Integer max = null;
        if (partTime && r.preferredWeeklyHours() != null) {
            if (r.preferredWeeklyHours().size() != 2 || r.preferredWeeklyHours().get(0) > r.preferredWeeklyHours().get(1)) {
                throw ApiException.badRequest("희망 근무시간은 최소와 최대 순서로 입력해 주세요.");
            }
            min = r.preferredWeeklyHours().get(0);
            max = r.preferredWeeklyHours().get(1);
        }
        worker.setMinWeeklyHours(min);
        worker.setMaxWeeklyHours(max);
        validateWeeklyHours(store, worker);

        List<WorkerAvailability> rows = partTime
                ? partRows(store, worker, r.availableParts())
                : fixedRows(worker, r.workHours());
        worker.getAvailability().clear();
        worker.getAvailability().addAll(rows);
        workerRepository.saveAndFlush(worker);
        return toResponse(worker, rows);
    }

    /**
     * 주 근로시간 상한. 5인 이상 사업장은 1주 40시간 + 연장 12시간 = 52시간을 넘길 수 없고(근로기준법 제50조·제53조),
     * 5인 미만은 이 한도가 적용되지 않지만 일주일(168시간)을 넘는 값은 입력할 수 없다.
     * 시급이 최저임금 미만인 경우는 막지 않는다(경고는 화면에서 보여준다).
     */
    private static void validateWeeklyHours(Store store, Worker worker) {
        int limit = store.getScale() == Store.Scale.OVER_5 ? MAX_WEEKLY_HOURS_OVER_5 : HOURS_PER_WEEK;
        for (Integer hours : new Integer[]{worker.getContractWeeklyHours(), worker.getMaxWeeklyHours()}) {
            if (hours != null && hours > limit) {
                throw ApiException.badRequest(store.getScale() == Store.Scale.OVER_5
                        ? "5인 이상 사업장은 주 52시간을 넘길 수 없습니다."
                        : "주 근로시간은 168시간을 넘길 수 없습니다.");
            }
        }
    }

    /** 알바생: 요일별 가능 파트 id 목록 → (요일, 파트) 한 줄씩. */
    private List<WorkerAvailability> partRows(Store store, Worker worker, List<List<String>> availableParts) {
        List<WorkerAvailability> rows = new ArrayList<>();
        if (availableParts == null) {
            return rows;
        }
        if (availableParts.size() > DAYS) {
            throw ApiException.badRequest("가능 파트는 요일별 7개까지 입력할 수 있습니다.");
        }
        Map<Long, Part> parts = store.getParts().stream().collect(Collectors.toMap(Part::getId, Function.identity()));
        for (int day = 0; day < availableParts.size(); day++) {
            List<String> ids = availableParts.get(day);
            if (ids == null) {
                continue;
            }
            Set<Long> seen = new HashSet<>();
            for (String id : ids) {
                Part part = parts.get(parseId(id));
                if (part == null) {
                    throw ApiException.badRequest("존재하지 않는 파트입니다: " + id);
                }
                if (seen.add(part.getId())) {
                    WorkerAvailability row = new WorkerAvailability();
                    row.setWorker(worker);
                    row.setDayOfWeek(Weekday.values()[day]);
                    row.setPart(part);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 직원: 요일별 고정 출퇴근 시각(null = 쉬는 날) → 있는 요일만 한 줄씩. */
    private List<WorkerAvailability> fixedRows(Worker worker, List<StaffDto.WorkTime> workHours) {
        List<WorkerAvailability> rows = new ArrayList<>();
        if (workHours == null) {
            return rows;
        }
        if (workHours.size() > DAYS) {
            throw ApiException.badRequest("고정 근무는 요일별 7개까지 입력할 수 있습니다.");
        }
        for (int day = 0; day < workHours.size(); day++) {
            StaffDto.WorkTime time = workHours.get(day);
            if (time == null) {
                continue;
            }
            WorkerAvailability row = new WorkerAvailability();
            row.setWorker(worker);
            row.setDayOfWeek(Weekday.values()[day]);
            LocalTime start = TimeCodec.start(time.start());
            row.setStartTime(start);
            row.setEndTime(TimeCodec.end(start, time.end()));
            rows.add(row);
        }
        return rows;
    }

    private StaffDto.Response toResponse(Worker w, List<WorkerAvailability> rows) {
        List<List<String>> availableParts = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            availableParts.add(new ArrayList<>());
        }
        List<StaffDto.WorkTime> workHours = null;
        for (WorkerAvailability row : rows) {
            int day = row.getDayOfWeek().ordinal();
            if (row.getPart() != null) {
                availableParts.get(day).add(String.valueOf(row.getPart().getId()));
            } else if (row.getStartTime() != null && row.getEndTime() != null) {
                if (workHours == null) {
                    workHours = new ArrayList<>(Collections.nCopies(DAYS, null));
                }
                workHours.set(day, new StaffDto.WorkTime(TimeCodec.formatStart(row.getStartTime()),
                        TimeCodec.formatEnd(row.getStartTime(), row.getEndTime())));
            }
        }
        List<Integer> preferred = w.getMinWeeklyHours() != null && w.getMaxWeeklyHours() != null
                ? List.of(w.getMinWeeklyHours(), w.getMaxWeeklyHours()) : null;
        // 프론트 타입이 title/phone 을 string 으로 보므로 비어 있으면 빈 문자열로 돌려준다.
        return new StaffDto.Response(w.getId(), w.getName(), w.getEmploymentType(), orEmpty(w.getTitle()), w.getHourlyWage(),
                w.isMinor(), w.getContractWeeklyHours(), preferred, availableParts, workHours, orEmpty(w.getPhone()));
    }

    private static Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("파트 id가 올바르지 않습니다: " + id);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
