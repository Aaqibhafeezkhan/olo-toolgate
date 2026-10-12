// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.care;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A mocked clinic: patient summaries, active medications, lab results, a drug interaction check, and appointment
 * booking. Every patient, value and rule is fictional and fixed, and none of it is medical advice; it exists so an
 * assistant for clinic staff can be designed and demonstrated without an EHR.
 *
 * <p>It also shows how to treat health data: patient ids are {@code sensitive} inputs, so ToolGate redacts them from
 * errors, logs and audit, and the booking tool takes a business key so a retried call books once.
 *
 * <p>Patients: MRN-1001 (type 2 diabetes, on warfarin), MRN-1002 (hypertension), MRN-1003 (depression, chronic pain).
 */
@ToolPackage(id = "examples.care", name = "Healthcare clinic (mocked)", publisher = "ToolGate Examples",
        description = "Mocked clinic data: patients, medications, labs, drug interactions and appointment booking. Fictional; not medical advice.")
public class Healthcare {
    public record Patient(
            @Param(description = "Medical record number") String mrn,
            @Param(description = "Name") String name,
            @Param(description = "Date of birth, ISO 8601") String dateOfBirth,
            @Param(description = "Sex recorded") String sex,
            @Param(description = "Known allergies") List<String> allergies,
            @Param(description = "Active problems") List<String> conditions,
            @Param(description = "Primary care provider") String primaryCareProvider,
            @Param(description = "Insurance plan") String insurancePlan) {}

    public record Medication(
            @Param(description = "Drug name, generic") String drug,
            @Param(description = "Dose") String dose,
            @Param(description = "How often") String frequency,
            @Param(description = "Started, ISO 8601") String since) {}

    public record LabResult(
            @Param(description = "Test") String test,
            @Param(description = "Value") double value,
            @Param(description = "Unit") String unit,
            @Param(description = "Reference range") String referenceRange,
            @Param(description = "NORMAL, HIGH or LOW") String flag,
            @Param(description = "Collected on, ISO 8601") String collected) {}

    static final class Data {
        static final Map<String, Patient> PATIENTS = Map.of(
                "MRN-1001", new Patient("MRN-1001", "Alex Moreno", "1961-04-17", "M", List.of("penicillin"),
                        List.of("Type 2 diabetes", "Atrial fibrillation"), "Dr. Ruth Adeyemi", "Contoso Health Gold"),
                "MRN-1002", new Patient("MRN-1002", "Grace Liu", "1975-11-02", "F", List.of(),
                        List.of("Essential hypertension", "Hyperlipidaemia"), "Dr. Samuel Novak", "Fabrikam Care Plus"),
                "MRN-1003", new Patient("MRN-1003", "Jordan Ellis", "1990-07-23", "X", List.of("sulfonamides"),
                        List.of("Major depressive disorder", "Chronic low back pain"), "Dr. Ruth Adeyemi", "Contoso Health Silver"));
        static final Map<String, List<Medication>> MEDICATIONS = Map.of(
                "MRN-1001", List.of(new Medication("metformin", "1000 mg", "twice daily", "2019-03-01"),
                        new Medication("warfarin", "5 mg", "once daily", "2023-08-14")),
                "MRN-1002", List.of(new Medication("lisinopril", "20 mg", "once daily", "2021-01-10"),
                        new Medication("simvastatin", "40 mg", "once daily at night", "2022-06-05")),
                "MRN-1003", List.of(new Medication("sertraline", "100 mg", "once daily", "2024-02-20")));
        static final Map<String, List<LabResult>> LABS = Map.of(
                "MRN-1001", List.of(new LabResult("HbA1c", 8.1, "%", "4.0-5.6", "HIGH", "2026-09-28"),
                        new LabResult("INR", 2.4, "ratio", "2.0-3.0 (on warfarin)", "NORMAL", "2026-10-05"),
                        new LabResult("eGFR", 74, "mL/min/1.73m2", ">60", "NORMAL", "2026-09-28")),
                "MRN-1002", List.of(new LabResult("LDL cholesterol", 3.9, "mmol/L", "<3.0", "HIGH", "2026-08-30"),
                        new LabResult("Potassium", 4.6, "mmol/L", "3.5-5.1", "NORMAL", "2026-08-30")),
                "MRN-1003", List.of(new LabResult("TSH", 2.1, "mIU/L", "0.4-4.0", "NORMAL", "2026-07-12")));

        record Interaction(String a, String b, String severity, String effect, String advice) {}

        static final List<Interaction> INTERACTIONS = List.of(
                new Interaction("warfarin", "aspirin", "MAJOR", "Higher risk of serious bleeding.",
                        "Avoid unless directed by a specialist; monitor for bleeding."),
                new Interaction("warfarin", "ibuprofen", "MAJOR", "Higher risk of gastrointestinal bleeding.",
                        "Prefer paracetamol for pain; avoid NSAIDs."),
                new Interaction("simvastatin", "clarithromycin", "MAJOR", "Raised statin levels; risk of muscle damage.",
                        "Pause simvastatin during the antibiotic course or choose another antibiotic."),
                new Interaction("sertraline", "tramadol", "MAJOR", "Risk of serotonin syndrome and seizures.",
                        "Choose another analgesic, or monitor closely at the lowest dose."),
                new Interaction("lisinopril", "spironolactone", "MODERATE", "Risk of high potassium.",
                        "Check potassium and kidney function within a week of starting."),
                new Interaction("metformin", "iodinated contrast", "MODERATE", "Risk of lactic acidosis if kidney function drops.",
                        "Hold metformin on the day of contrast imaging and recheck eGFR before restarting."),
                new Interaction("lisinopril", "ibuprofen", "MINOR", "May blunt the blood-pressure effect.",
                        "Use the lowest dose for the shortest time."));

        record Slot(String slotId, String specialty, String clinician, String start, int minutes) {}

        static final List<Slot> SLOTS = List.of(
                new Slot("S-201", "PRIMARY_CARE", "Dr. Ruth Adeyemi", "2026-10-14T09:00", 20),
                new Slot("S-202", "PRIMARY_CARE", "Dr. Samuel Novak", "2026-10-14T11:40", 20),
                new Slot("S-203", "PRIMARY_CARE", "Dr. Ruth Adeyemi", "2026-10-15T15:20", 20),
                new Slot("S-301", "CARDIOLOGY", "Dr. Ines Ferreira", "2026-10-16T10:00", 40),
                new Slot("S-302", "CARDIOLOGY", "Dr. Ines Ferreira", "2026-10-21T14:00", 40),
                new Slot("S-401", "ENDOCRINOLOGY", "Dr. Arjun Mehta", "2026-10-20T08:30", 30),
                new Slot("S-501", "PSYCHIATRY", "Dr. Hannah Brooks", "2026-10-22T13:00", 50));
    }

    static final String MRN_HELP = "Medical record number, such as MRN-1001";

    @ServerTool(name = "patient", title = "Patient summary",
            description = "Returns a patient's summary: demographics, allergies, active problems, care provider and plan.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "PATIENT_NOT_FOUND", description = "No patient has that record number."))
    @Example(arguments = "{\"mrn\":\"MRN-0000\"}", expectedError = "PATIENT_NOT_FOUND")
    public Patient patient(@Param(description = MRN_HELP, maxLength = 20, sensitive = true) String mrn, ToolContext context) {
        return find(Data.PATIENTS, mrn);
    }

    public record Medications(@Param(description = "Active medications") List<Medication> medications) {}

    @ServerTool(name = "medications", title = "Active medications",
            description = "Lists a patient's active medications with dose and frequency.", effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "PATIENT_NOT_FOUND", description = "No patient has that record number."))
    @Example(arguments = "{\"mrn\":\"MRN-1003\"}", expectedOutput = "{\"medications\":[{\"drug\":\"sertraline\",\"dose\":\"100 mg\","
            + "\"frequency\":\"once daily\",\"since\":\"2024-02-20\"}]}")
    public Medications medications(@Param(description = MRN_HELP, maxLength = 20, sensitive = true) String mrn, ToolContext context) {
        return new Medications(find(Data.MEDICATIONS, mrn));
    }

    public record Labs(
            @Param(description = "Results, newest first") List<LabResult> results,
            @Param(description = "How many are outside their reference range") int abnormal) {}

    @ServerTool(name = "labs", title = "Lab results",
            description = "Returns a patient's recent lab results with reference ranges and HIGH/LOW flags.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "PATIENT_NOT_FOUND", description = "No patient has that record number."))
    public Labs labs(
            @Param(description = MRN_HELP, maxLength = 20, sensitive = true) String mrn,
            @Param(description = "Only this test, such as HbA1c", required = false, maxLength = 60) String test,
            ToolContext context) {
        List<LabResult> results = find(Data.LABS, mrn).stream()
                .filter(r -> test == null || r.test().equalsIgnoreCase(test.trim()))
                .sorted((a, b) -> b.collected().compareTo(a.collected()))
                .toList();
        return new Labs(results, (int) results.stream().filter(r -> !r.flag().equals("NORMAL")).count());
    }

    public record Interaction(
            @Param(description = "First drug") String drugA,
            @Param(description = "Second drug") String drugB,
            @Param(description = "MAJOR, MODERATE or MINOR") String severity,
            @Param(description = "What can happen") String effect,
            @Param(description = "Suggested handling") String advice) {}

    public record InteractionCheck(
            @Param(description = "Drugs checked, including the patient's active medications when an MRN was given") List<String> checked,
            @Param(description = "Interactions found, most severe first") List<Interaction> interactions,
            @Param(description = "Drugs the patient is allergic to among those checked") List<String> allergyAlerts,
            @Param(description = "Reminder that the data is mocked") String disclaimer) {}

    @ServerTool(name = "interactions", title = "Check drug interactions",
            description = "Checks a list of drugs, optionally together with a patient's active medications and"
                    + " allergies, against the (mocked) interaction table.",
            useWhen = "Use it before suggesting or prescribing a new medication.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "PATIENT_NOT_FOUND", description = "No patient has that record number."))
    @Example(arguments = "{\"drugs\":[\"Aspirin\"],\"mrn\":\"MRN-1001\"}",
            expectedOutput = "{\"checked\":[\"aspirin\",\"metformin\",\"warfarin\"],\"interactions\":[{\"drugA\":\"warfarin\","
                    + "\"drugB\":\"aspirin\",\"severity\":\"MAJOR\",\"effect\":\"Higher risk of serious bleeding.\","
                    + "\"advice\":\"Avoid unless directed by a specialist; monitor for bleeding.\"}],\"allergyAlerts\":[],"
                    + "\"disclaimer\":\"Mocked data for demonstration; not medical advice.\"}")
    public InteractionCheck interactions(
            @Param(description = "Drug names, generic, such as [\"ibuprofen\"]") List<String> drugs,
            @Param(description = "Also include this patient's medications and allergies", required = false, maxLength = 20,
                    sensitive = true) String mrn,
            ToolContext context) {
        if (drugs == null || drugs.isEmpty() || drugs.size() > 20) throw new ToolInputError("give 1 to 20 drugs");
        Set<String> all = new LinkedHashSet<>();
        for (String d : drugs) all.add(d.trim().toLowerCase(Locale.ROOT));
        List<String> allergies = List.of();
        if (mrn != null && !mrn.isBlank()) {
            find(Data.MEDICATIONS, mrn).forEach(m -> all.add(m.drug()));
            allergies = find(Data.PATIENTS, mrn).allergies();
        }
        List<Interaction> found = new ArrayList<>();
        for (Data.Interaction i : Data.INTERACTIONS) {
            if (all.contains(i.a()) && all.contains(i.b())) found.add(new Interaction(i.a(), i.b(), i.severity(), i.effect(), i.advice()));
        }
        List<String> severity = List.of("MAJOR", "MODERATE", "MINOR");
        found.sort((x, y) -> Integer.compare(severity.indexOf(x.severity()), severity.indexOf(y.severity())));
        List<String> alerts = allergies.stream().filter(all::contains).toList();
        return new InteractionCheck(List.copyOf(all), found, alerts, "Mocked data for demonstration; not medical advice.");
    }

    public record Slot(
            @Param(description = "Slot id, to book with care.book") String slotId,
            @Param(description = "Specialty") String specialty,
            @Param(description = "Clinician") String clinician,
            @Param(description = "Start, local time ISO 8601") String start,
            @Param(description = "Length in minutes") int minutes) {}

    public record Slots(@Param(description = "Open slots, soonest first") List<Slot> slots) {}

    @ServerTool(name = "slots", title = "Find appointment slots",
            description = "Lists open appointment slots for a specialty, optionally with one clinician or from a date.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"specialty\":\"CARDIOLOGY\",\"from\":\"2026-10-20\"}",
            expectedOutput = "{\"slots\":[{\"slotId\":\"S-302\",\"specialty\":\"CARDIOLOGY\",\"clinician\":\"Dr. Ines Ferreira\","
                    + "\"start\":\"2026-10-21T14:00\",\"minutes\":40}]}")
    public Slots slots(
            @Param(description = "Specialty", enumValues = {"PRIMARY_CARE", "CARDIOLOGY", "ENDOCRINOLOGY", "PSYCHIATRY"}) String specialty,
            @Param(description = "Clinician name contains", required = false, maxLength = 60) String clinician,
            @Param(description = "Earliest date, ISO 8601", required = false, pattern = "^\\d{4}-\\d{2}-\\d{2}$") String from,
            ToolContext context) {
        return new Slots(Data.SLOTS.stream()
                .filter(s -> s.specialty().equals(specialty))
                .filter(s -> clinician == null || s.clinician().toLowerCase(Locale.ROOT).contains(clinician.toLowerCase(Locale.ROOT)))
                .filter(s -> from == null || s.start().compareTo(from) >= 0)
                .map(s -> new Slot(s.slotId(), s.specialty(), s.clinician(), s.start(), s.minutes()))
                .toList());
    }

    public record Booking(
            @Param(description = "Confirmation number") String confirmation,
            @Param(description = "Slot booked") Slot slot,
            @Param(description = "What the patient is told") String patientMessage) {}

    @ServerTool(name = "book", title = "Book an appointment",
            description = "Books an open slot for a patient (mocked: nothing is stored). Booking again with the same"
                    + " reference returns the first booking.",
            useWhen = "Use it only after the user confirms the patient, slot and reason.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = {
                @ErrorDecl(code = "PATIENT_NOT_FOUND", description = "No patient has that record number."),
                @ErrorDecl(code = "SLOT_NOT_AVAILABLE", description = "No open slot has that id.")})
    @Example(arguments = "{\"mrn\":\"MRN-1002\",\"slotId\":\"S-999\",\"reason\":\"BP review\",\"reference\":\"r-1\"}",
            expectedError = "SLOT_NOT_AVAILABLE")
    public Booking book(
            @Param(description = MRN_HELP, maxLength = 20, sensitive = true) String mrn,
            @Param(description = "Slot id from care.slots", maxLength = 20) String slotId,
            @Param(description = "Reason for the visit", maxLength = 200, sensitive = true) String reason,
            @Param(description = "Your request id; a repeat with the same reference books once", minLength = 1, maxLength = 64)
            String reference,
            ToolContext context) {
        Patient p = find(Data.PATIENTS, mrn);
        Data.Slot s = Data.SLOTS.stream().filter(x -> x.slotId().equalsIgnoreCase(slotId.trim())).findFirst()
                .orElseThrow(() -> new ToolBusinessError("SLOT_NOT_AVAILABLE", "No open slot has that id."));
        Slot slot = new Slot(s.slotId(), s.specialty(), s.clinician(), s.start(), s.minutes());
        return new Booking("APT-" + shortHash(reference + mrn + slotId), slot,
                "Hi " + p.name().split(" ")[0] + ", you're booked with " + s.clinician() + " on "
                        + s.start().replace('T', ' ') + ". Please arrive 10 minutes early.");
    }

    static <T> T find(Map<String, T> data, String mrn) {
        T value = data.get(mrn.trim().toUpperCase(Locale.ROOT));
        if (value == null) throw new ToolBusinessError("PATIENT_NOT_FOUND", "No patient has that record number. Mocked patients are MRN-1001 to MRN-1003.");
        return value;
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
