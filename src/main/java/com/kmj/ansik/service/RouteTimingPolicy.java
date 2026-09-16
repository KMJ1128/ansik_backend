package com.kmj.ansik.service;

import com.kmj.ansik.dto.AiCourseDto.CourseStop;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline planning estimates, NOT routing-provider travel times or verified opening hours. */
final class RouteTimingPolicy {
    private RouteTimingPolicy() {}

    /** At most seven daily stops normally. Preserve first stop and restaurant slots. */
    static List<CourseStop> order(List<CourseStop> stops) {
        if (stops.size() < 3 || stops.size() > 8) return List.copyOf(stops);
        List<CourseStop> best = new ArrayList<>(stops);
        double[] bestLength = {length(stops)};
        search(stops, new ArrayList<>(), new boolean[stops.size()], best, bestLength);
        return List.copyOf(best);
    }

    private static void search(List<CourseStop> source, List<CourseStop> route, boolean[] used,
                               List<CourseStop> best, double[] bestLength) {
        int slot = route.size();
        if (slot == source.size()) {
            double length = length(route);
            if (length + 0.000001 < bestLength[0]) {
                bestLength[0] = length;
                best.clear(); best.addAll(route);
            }
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            if (used[i]) continue;
            // A meal selected for a particular position may not become a morning stop.
            if ((slot == 0 || "RESTAURANT".equals(source.get(slot).category())) && i != slot) continue;
            if ((i == 0 || "RESTAURANT".equals(source.get(i).category())) && i != slot) continue;
            used[i] = true; route.add(source.get(i));
            search(source, route, used, best, bestLength);
            route.remove(route.size() - 1); used[i] = false;
        }
    }

    private static double length(List<CourseStop> stops) {
        double total = 0;
        for (int i = 1; i < stops.size(); i++) {
            double km = distance(stops.get(i - 1), stops.get(i));
            if (!Double.isFinite(km)) return Double.POSITIVE_INFINITY;
            total += km;
        }
        return total;
    }

    static List<CourseStop> estimate(List<CourseStop> stops) {
        List<CourseStop> result = new ArrayList<>();
        int minute = 9 * 60;
        for (int i = 0; i < stops.size(); i++) {
            CourseStop stop = stops.get(i);
            double km = i == 0 ? 0 : distance(stops.get(i - 1), stop);
            boolean unknown = !valid(stop) || !Double.isFinite(km);
            // Explicit planning assumptions: 1.4 detour factor; walking nearby,
            // otherwise 18km/h with 15-minute access/wait buffer. No live traffic claim.
            int transfer = i == 0 ? 0 : unknown ? 30
                    : (int) (5 * Math.ceil((km < 1.2 ? km * 1.4 / 4 * 60 + 5
                    : km * 1.4 / 18 * 60 + 15) / 5));
            minute += transfer;
            int stay = switch (stop.category() == null ? "" : stop.category()) {
                case "RESTAURANT" -> 60;
                case "SHOPPING" -> 75;
                default -> 90;
            };
            result.add(new CourseStop(stop.id(), stop.name(), stop.address(), stop.category(),
                    stop.imageUrl(), stop.latitude(), stop.longitude(),
                    String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60),
                    stop.reason(), stop.visitTip(), stop.healthNote(), unknown ? -1 : transfer,
                    stay, true, unknown || km > 40 || minute + stay > 20 * 60));
            minute += stay;
        }
        return List.copyOf(result);
    }

    static double distance(CourseStop a, CourseStop b) {
        if (!valid(a) || !valid(b)) return Double.NaN;
        double lat = Math.toRadians(b.latitude() - a.latitude());
        double lon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(a.latitude()))
                * Math.cos(Math.toRadians(b.latitude())) * Math.pow(Math.sin(lon / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
    }

    private static boolean valid(CourseStop s) {
        return Double.isFinite(s.latitude()) && Double.isFinite(s.longitude())
                && s.latitude() >= 33 && s.latitude() <= 39
                && s.longitude() >= 124 && s.longitude() <= 132;
    }
}
