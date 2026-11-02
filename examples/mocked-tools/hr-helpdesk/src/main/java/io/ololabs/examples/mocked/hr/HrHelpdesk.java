// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.hr;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.JwtClaim;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ModelValidation;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingPrompt;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.annotations.ValuePhrasing;
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
    @Training(
            phrasings = {
                "Who is {query}?",
                "Find {query} in the directory",
                "Who does {query} report to?",
                "Which team is {query} in?",
                "Look up colleague {query}",
                "What's {query}'s job title and office?"
            },
            negatives = {
                "How do I write a good employee review?",
                "What makes a good manager?"
            },
            parameters = @TrainingValues(name = "query",
                    examples = {
                        "Priya",
                        "E-1042",
                        "jane.smith",
                        "Okafor",
                        "m.rossi@example.com",
                        "Lars Berg"
                    }))
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "who manages priya in the directory?", arguments = "{\"query\":\"priya\"}"),
                @TrainingPrompt(prompt = "Find E-1042's title and team", arguments = "{\"query\":\"E-1042\"}")
            },
            negatives = {
                "Out of curiosity, how do I write a good employee review?"
            })
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
    @Training(
            phrasings = {
                "{question}",
                "What does the HR policy say: {question}",
                "Check the handbook: {question}",
                "HR question: {question}",
                "Find the HR policy for this: {question}",
                "{question} What does the policy say?",
                "According to HR, {question}",
                "HR policy question: {question}",
                "What does the handbook say about this: {question}"
            },
            negatives = {
                "What is my insurance policy number?",
                "Write a remote-work policy for a startup",
                "Is my car insurance policy still valid?"
            },
            parameters = @TrainingValues(name = "question",
                    examples = {
                        "How many days of annual leave do I get?",
                        "Can I work from home on Fridays?",
                        "What's the parental leave policy?",
                        "Do unused holidays carry over to next year?",
                        "How do I claim travel expenses?",
                        "What is the notice period if I resign?"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "How many days of annual leave do I get?", arguments = "{\"question\":\"How many days of annual leave do I get?\"}"),
                @TrainingPrompt(prompt = "Check the handbook: \"Can I work from home on Fridays?\"", arguments = "{\"question\":\"Can I work from home on Fridays?\"}"),
                @TrainingPrompt(prompt = "What is the notice period if I resign?", arguments = "{\"question\":\"What is the notice period if I resign?\"}"),
                @TrainingPrompt(prompt = "What's the parental leave policy?", arguments = "{\"question\":\"What's the parental leave policy?\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "am I allowed to carry over unused vacation days?", arguments = "{\"question\":\"am I allowed to carry over unused vacation days?\"}"),
                @TrainingPrompt(prompt = "What does our policy say about overtime pay?", arguments = "{\"question\":\"What does our policy say about overtime pay?\"}")
            },
            negatives = {
                "Out of curiosity, what is my insurance policy number?",
                "Write a remote work policy for a startup"
            })
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
    @Training(
            phrasings = {
                "How much leave do I have left?",
                "What's my holiday balance?",
                "How many sick days do I have?",
                "My leave balances please",
                "How many vacation days are left this year?",
                "How many holidays have I got left?",
                "What's my annual leave balance?",
                "Do I have any leave left?",
                "How many days off can I still take?",
                "Check my leave balances",
                "How much vacation have I used?",
                "My remaining sick leave?",
                "How many annual leave days remain for me?",
                "What's left of my holiday allowance?",
                "leave balance?",
                "Can I still take a week off this year?",
                "Show my leave",
                "How much parental leave do I have?",
                "How many PTO days do I have?",
                "Remaining days off for me",
                "Check how much time off I have",
                "What's my time-off balance?",
                "How many holiday days are left for me this year?"
            },
            negatives = "How many vacation days is normal in Germany?",
            defaultPhrasings = {
                "Check my holiday days",
                "How many leave days do I have left?",
                "What's my leave balance?"
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "how many days off do i still have this year", arguments = "{}"),
                @TrainingPrompt(prompt = "My sick leave balance?", arguments = "{}")
            },
            negatives = {
                "How many public holidays does Germany have?"
            })
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
                @ErrorDecl(code = "NOT_AN_EMPLOYEE", description = "The signed-in user is not in the directory.")}, reconciliation = Reconciliation.MANUAL)
    @JwtClaim(name = "employeeId", claim = "employee_id")
    @Training(
            phrasings = {
                "Book {type} leave from {from} to {to}, request {reference}",
                "I'd like to take {type} leave {from} until {to} (ref {reference})",
                "Request {type} leave between {from} and {to}, id {reference}",
                "Put in for {type} leave, {from} to {to}. Reference {reference}",
                "Book {type} leave from {from} to {to}, reference {reference}",
                "Request {type} from {from} to {to}, ref {reference}"
            },
            negatives = "How do I ask my manager for time off?",
            parameters = {
                @TrainingValues(name = "type",
                    phrasings = {
                        @ValuePhrasing(value = "ANNUAL", phrasings = {
                            "annual",
                            "vacation",
                            "holiday"
                        }),
                        @ValuePhrasing(value = "SICK", phrasings = "sick"),
                        @ValuePhrasing(value = "PARENTAL", phrasings = "parental")
                    }),
                @TrainingValues(name = "from",
                    examples = {
                        "2026-11-03",
                        "2026-11-10",
                        "2026-12-01",
                        "2026-11-17",
                        "2026-12-21"
                    }),
                @TrainingValues(name = "to",
                    examples = {
                        "2026-11-07",
                        "2026-11-14",
                        "2026-12-05",
                        "2026-11-21",
                        "2027-01-02"
                    }),
                @TrainingValues(name = "reference",
                    examples = {
                        "case-4471",
                        "req-20261031-01",
                        "conv-88213",
                        "ticket-flow-17",
                        "chat-5520",
                        "REQ-3307"
                    })
            },
            prompts = {
                @TrainingPrompt(prompt = "I'd like to take annual leave 2026-11-03 until 2027-01-02 (ref REQ-3307)", arguments = "{\"type\":\"ANNUAL\",\"from\":\"2026-11-03\",\"to\":\"2027-01-02\",\"reference\":\"REQ-3307\"}"),
                @TrainingPrompt(prompt = "Book vacation from 2026-11-10 to 2026-11-14, reference chat-5520", arguments = "{\"type\":\"ANNUAL\",\"from\":\"2026-11-10\",\"to\":\"2026-11-14\",\"reference\":\"chat-5520\"}"),
                @TrainingPrompt(prompt = "I'm sick, request leave 2026-11-17 to 2026-11-21, ref ticket-flow-17", arguments = "{\"type\":\"SICK\",\"from\":\"2026-11-17\",\"to\":\"2026-11-21\",\"reference\":\"ticket-flow-17\"}"),
                @TrainingPrompt(prompt = "Parental leave from 2026-11-10 to 2026-12-05, reference case-4471", arguments = "{\"type\":\"PARENTAL\",\"from\":\"2026-11-10\",\"to\":\"2026-12-05\",\"reference\":\"case-4471\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "put me down for holiday 2026-12-22 to 2026-12-31, ref REQ-9001", arguments = "{\"type\":\"ANNUAL\",\"from\":\"2026-12-22\",\"to\":\"2026-12-31\",\"reference\":\"REQ-9001\"}"),
                @TrainingPrompt(prompt = "Need sick days 2026-11-04 through 2026-11-05 (ref chat-77)", arguments = "{\"type\":\"SICK\",\"from\":\"2026-11-04\",\"to\":\"2026-11-05\",\"reference\":\"chat-77\"}")
            },
            negatives = {
                "How much notice do I need to give for leave?"
            })
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
