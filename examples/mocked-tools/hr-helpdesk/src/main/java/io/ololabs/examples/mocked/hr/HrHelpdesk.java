// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.hr;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.JwtClaim;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A mocked HR helpdesk: the employee directory, policy answers with their section, leave balances and leave requests.
 * Fictional, fixed data.
 *
 * <p>It shows identity: {@code my-leave} and {@code request-leave} act for the caller, whose employee id comes from
 * the verified token ({@code @JwtClaim} on those two tools), never from an argument, so one employee cannot read or book another's
 * leave. In {@code toolgate dev} the claim comes from {@code toolgate.dev.json}; under ToolGate, from the caller's
 * identity token.
 *
 * <p>Employees E-100 (engineering manager), E-101 and E-102 (engineers, report to E-100), E-200 (HR partner).
 */
@ToolPackage(id = "examples.hr", name = "HR helpdesk (mocked)", publisher = "ToolGate Examples",
        description = "Mocked HR helpdesk: directory, policy answers, leave balances and leave requests for the caller.")
public class HrHelpdesk {
    public record Employee(
            @Param(description = "Employee id") String employeeId,
            @Param(description = "Name") String name,
            @Param(description = "Job title") String title,
            @Param(description = "Department") String department,
            @Param(description = "Manager's employee id, or empty") String managerId,
            @Param(description = "Office") String location,
            @Param(description = "Work email") String email) {}

    record Policy(String section, String title, Set<String> keywords, String text) {}

    static final class Data {
        static final Map<String, Employee> EMPLOYEES = Map.of(
                "E-100", new Employee("E-100", "Fatima Rahman", "Engineering Manager", "Engineering", "", "Bengaluru", "fatima.rahman@example.com"),
                "E-101", new Employee("E-101", "Lukas Becker", "Senior Software Engineer", "Engineering", "E-100", "Berlin", "lukas.becker@example.com"),
                "E-102", new Employee("E-102", "Aisha Bello", "Software Engineer", "Engineering", "E-100", "Bengaluru", "aisha.bello@example.com"),
                "E-200", new Employee("E-200", "Tom Nguyen", "HR Business Partner", "People", "", "Remote", "tom.nguyen@example.com"));
        static final Map<String, Map<String, Double>> LEAVE = Map.of(
                "E-100", Map.of("ANNUAL", 14.0, "SICK", 10.0, "PARENTAL", 0.0),
                "E-101", Map.of("ANNUAL", 6.5, "SICK", 8.0, "PARENTAL", 0.0),
                "E-102", Map.of("ANNUAL", 18.0, "SICK", 12.0, "PARENTAL", 0.0),
                "E-200", Map.of("ANNUAL", 21.0, "SICK", 12.0, "PARENTAL", 0.0));
        static final List<Policy> POLICIES = List.of(
                new Policy("HR-2.1", "Annual leave", Set.of("annual", "vacation", "holiday", "leave", "carry", "pto"),
                        "Employees accrue 24 days of annual leave a year. Up to 5 unused days carry over to the next year and"
                                + " expire on 31 March. Requests of more than 5 working days need 2 weeks' notice."),
                new Policy("HR-2.4", "Sick leave", Set.of("sick", "ill", "doctor", "medical", "leave"),
                        "Up to 12 paid sick days a year. A doctor's note is needed for absences longer than 3 consecutive days."),
                new Policy("HR-3.2", "Remote work", Set.of("remote", "home", "wfh", "hybrid", "abroad"),
                        "Hybrid employees work from the office at least 2 days a week. Working from another country needs"
                                + " HR approval for stays longer than 10 working days a year."),
                new Policy("HR-4.1", "Expenses", Set.of("expense", "expenses", "reimbursement", "travel", "meal", "receipt"),
                        "Submit expenses within 30 days with itemised receipts. Meals while travelling are reimbursed up to"
                                + " 60 EUR a day."),
                new Policy("HR-5.3", "Parental leave", Set.of("parental", "maternity", "paternity", "adoption", "baby", "leave"),
                        "Primary caregivers receive 26 weeks of paid leave and secondary caregivers 6 weeks, after 6 months of service."));
    }

    @ServerTool(name = "employee", title = "Look up an employee",
            description = "Finds colleagues in the directory by id, name or email fragment, with title, team, manager and office.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"query\":\"becker\"}", expectedOutput = "{\"employees\":[{\"employeeId\":\"E-101\",\"name\":\"Lukas Becker\","
            + "\"title\":\"Senior Software Engineer\",\"department\":\"Engineering\",\"managerId\":\"E-100\",\"location\":\"Berlin\","
            + "\"email\":\"lukas.becker@example.com\"}]}")
    public Employees employee(@Param(description = "Employee id, name or email, or part of one", minLength = 2, maxLength = 100) String query,
            ToolContext context) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        return new Employees(Data.EMPLOYEES.values().stream()
                .filter(e -> e.employeeId().equalsIgnoreCase(q) || e.name().toLowerCase(Locale.ROOT).contains(q)
                        || e.email().contains(q))
                .sorted(Comparator.comparing(Employee::employeeId)).toList());
    }

    public record Employees(@Param(description = "Matches") List<Employee> employees) {}

    public record PolicyAnswer(
            @Param(description = "Policy section, to cite") String section,
            @Param(description = "Policy title") String title,
            @Param(description = "Policy text") String text) {}

    public record PolicyAnswers(@Param(description = "Relevant policy sections, best match first; empty when none applies") List<PolicyAnswer> sections) {}

    @ServerTool(name = "policy", title = "Search HR policy",
            description = "Finds the HR policy sections relevant to a question, with their text, so the answer can cite them.",
            useWhen = "Use it for any question about leave, remote work, expenses or benefits; quote the section in the answer.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"question\":\"Can I carry over unused vacation days?\"}", expectedOutput = "{\"sections\":[{\"section\":\"HR-2.1\","
            + "\"title\":\"Annual leave\",\"text\":\"Employees accrue 24 days of annual leave a year. Up to 5 unused days carry over to the"
            + " next year and expire on 31 March. Requests of more than 5 working days need 2 weeks' notice.\"}]}")
    public PolicyAnswers policy(@Param(description = "The employee's question", minLength = 2, maxLength = 500) String question,
            ToolContext context) {
        Set<String> words = Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^a-z]+")).collect(Collectors.toSet());
        return new PolicyAnswers(Data.POLICIES.stream()
                .map(p -> Map.entry(p, p.keywords().stream().filter(words::contains).count()))
                .filter(e -> e.getValue() > 0)
                // "leave" alone matches several sections; more matching words rank higher.
                .sorted(Map.Entry.<Policy, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> new PolicyAnswer(e.getKey().section(), e.getKey().title(), e.getKey().text()))
                .toList());
    }

    public record Balance(
            @Param(description = "Employee id") String employeeId,
            @Param(description = "Annual leave days left") double annual,
            @Param(description = "Sick days left") double sick,
            @Param(description = "Parental leave days left") double parental) {}

    @ServerTool(name = "my-leave", title = "My leave balance",
            description = "Returns the caller's own leave balances. The caller is identified by their sign-in, not by an argument.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "NOT_AN_EMPLOYEE", description = "The signed-in user is not in the directory."))
    @JwtClaim(name = "employeeId", claim = "employee_id")
    public Balance myLeave(ToolContext context) {
        String me = me(context);
        Map<String, Double> b = Data.LEAVE.get(me);
        return new Balance(me, b.get("ANNUAL"), b.get("SICK"), b.get("PARENTAL"));
    }

    public record LeaveRequest(
            @Param(description = "Request id") String requestId,
            @Param(description = "Working days requested") int workingDays,
            @Param(description = "Who approves it") String approver,
            @Param(description = "Balance left if approved") double balanceAfter,
            @Param(description = "PENDING_APPROVAL") String status) {}

    @ServerTool(name = "request-leave", title = "Request leave",
            description = "Requests leave for the caller between two dates (mocked: nothing is stored) and routes it to"
                    + " their manager. A repeat with the same reference returns the first request.",
            useWhen = "Use it only after the employee confirms the dates and type.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = {
                @ErrorDecl(code = "INSUFFICIENT_BALANCE", description = "Not enough leave of that type left."),
                @ErrorDecl(code = "NOT_AN_EMPLOYEE", description = "The signed-in user is not in the directory.")})
    @JwtClaim(name = "employeeId", claim = "employee_id")
    public LeaveRequest requestLeave(
            @Param(description = "Type", enumValues = {"ANNUAL", "SICK", "PARENTAL"}) String type,
            @Param(description = "First day, ISO 8601", pattern = "^\\d{4}-\\d{2}-\\d{2}$") String from,
            @Param(description = "Last day, ISO 8601", pattern = "^\\d{4}-\\d{2}-\\d{2}$") String to,
            @Param(description = "Your request id; a repeat with the same reference is filed once", minLength = 1, maxLength = 64)
            String reference,
            ToolContext context) {
        String me = me(context);
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            throw new ToolInputError("from and to must be dates such as 2026-11-02");
        }
        if (end.isBefore(start) || start.plusDays(366).isBefore(end)) throw new ToolInputError("to must be on or after from, within a year");
        int days = (int) start.datesUntil(end.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY).count();
        double left = Data.LEAVE.get(me).get(type);
        if (days > left) {
            throw new ToolBusinessError("INSUFFICIENT_BALANCE", "That is " + days + " working days; " + left + " " + type.toLowerCase(Locale.ROOT) + " days are left.");
        }
        String manager = Data.EMPLOYEES.get(me).managerId();
        String approver = manager.isEmpty() ? "E-200 (HR)" : manager + " (" + Data.EMPLOYEES.get(manager).name() + ")";
        return new LeaveRequest("LR-" + shortHash(me + reference), days, approver, left - days, "PENDING_APPROVAL");
    }

    static String me(ToolContext context) {
        String id = context.jwt().getStringValue("employeeId").trim().toUpperCase(Locale.ROOT);
        if (!Data.EMPLOYEES.containsKey(id)) throw new ToolBusinessError("NOT_AN_EMPLOYEE", "The signed-in user is not in the directory.");
        return id;
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
