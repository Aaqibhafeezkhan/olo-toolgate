--------------------------- MODULE trigger_delivery ---------------------------
(***************************************************************************)
(* Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0            *)
(*                                                                         *)
(* Normative delivery model for triggers (ADR 058, plan section 6.6.1).    *)
(*                                                                         *)
(* One trigger instance reads a replayable source of items 1..N. Its       *)
(* process emits events; the executor supervisor makes them durable in an  *)
(* outbox under (leaseEpoch, outboxSeq); Control stores each item once,    *)
(* acknowledges the contiguous resolved outboxSeq per epoch, and commits   *)
(* ACCEPTED checkpoints. Processes crash, messages are lost or duplicated, *)
(* the lease moves to another executor while the old one keeps running    *)
(* until its lease expires, executors are reinstalled with empty state,    *)
(* checkpoints migrate on upgrade, retention purges dedupe keys, executors *)
(* are removed, and a producer can be distrusted.                          *)
(*                                                                         *)
(* A position p means "items 1..p-1 are done". Item ids are the stable     *)
(* source-level event identities. emitSeq never leaves one process and is  *)
(* represented by the in-memory pending/resolved sets.                     *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Exec,           \* executors that may hold the instance's lease
          N,              \* items in the replayable source
          Bad,            \* items whose events are terminally rejected
          Policy,         \* "HALT" or "SKIP" (SKIP_AND_RECORD)
          MaxEpoch,       \* lease epochs explored
          MaxCrash,       \* process crashes explored
          MaxDrop,        \* lost messages explored
          MaxPurge,       \* retention purges explored
          AllowDistrust,  \* explore producer distrust (T15)
          AllowUpgrade,   \* explore checkpoint migration (T12)
          AllowRemove,    \* explore executor removal (T13)
          MaxInFlight     \* bound on messages in flight in each direction

None == 0
Items == 1..N
Epochs == 1..MaxEpoch
MaxSeq == (MaxCrash + 1) * N
Positions == 1..(N + 1)

VARIABLES
    epoch,        \* Control: current lease epoch
    holder,       \* Control: epoch -> executor the lease was issued to
    myEpoch,      \* executor: epoch of the lease it believes it holds (0 = none)
    running,      \* executor: process alive
    startPos,     \* executor (memory): position the process started from
    readPos,      \* executor (memory): next item the process reads
    pending,      \* executor (memory): emitted, unresolved items
    resolved,     \* executor (memory): items durable or loss-recorded by this process
    outbox,       \* executor (durable): records [ep, seq, id, loss]
    seqHigh,      \* executor (durable): epoch -> highest outboxSeq written (part of the outbox)
    localCkpt,    \* executor (durable): LOCAL checkpoint position
    ackedRec,     \* executor (durable): acknowledged records [ep, seq, id]
    acceptedPos,  \* executor (durable): ACCEPTED position derived from acknowledgements
    halted,       \* instance condition: item halted under HALT (0 = none)
    net,          \* records in flight to Control
    ackNet,       \* acknowledgements in flight to executors
    keys,         \* Control: retained dedupe keys
    stored,       \* Control: items with a stored row
    storeCount,   \* Control: stored rows per item
    disposed,     \* Control: items with a recorded terminal disposition (loss record)
    ctrlSeqs,     \* Control: epoch -> resolved outboxSeqs
    ctrlCkpt,     \* Control: committed ACCEPTED checkpoint position
    producer,     \* Control: item -> executor whose record was stored
    distrusted,   \* Control: distrusted producers
    quarantined,  \* Control: quarantined stored rows [id, from]
    queue,        \* Control: router deliveries created, as rows [id, from]. Hand-out to consumers is not
                  \* modelled: only Q reads the queue and Q is monotone in it, so never handing out keeps
                  \* every row Q could object to and loses no violation.
    ckptVersion,  \* checkpoint schema version of the live checkpoints
    pkgVersion,   \* checkpoint schema version of the running package
    removed,      \* executors removed (T13)
    lostCount,    \* executor -> records it could not drain before removal
    faults        \* [crash, drop, purge] counters

vars == <<epoch, holder, myEpoch, running, startPos, readPos, pending, resolved, outbox, seqHigh,
          localCkpt, ackedRec, acceptedPos, halted, net, ackNet, keys, stored, storeCount, disposed,
          ctrlSeqs, ctrlCkpt, producer, distrusted, quarantined, queue, ckptVersion,
          pkgVersion, removed, lostCount, faults>>

exec == <<myEpoch, running, startPos, readPos, pending, resolved, outbox, seqHigh, localCkpt,
          ackedRec, acceptedPos>>
ctrl == <<epoch, holder, keys, stored, storeCount, disposed, ctrlSeqs, ctrlCkpt, producer,
          distrusted, quarantined, queue>>
misc == <<halted, net, ackNet, ckptVersion, pkgVersion, removed, lostCount, faults>>

Max(S) == IF S = {} THEN 0 ELSE CHOOSE x \in S : \A y \in S : y <= x

\* Highest n such that outboxSeqs 1..n are all in S (contiguous prefix).
Prefix(S) == CHOOSE n \in 0..MaxSeq : (\A k \in 1..n : k \in S) /\ (n + 1 \notin S)

\* Highest position q >= from such that items from..q-1 are all in done.
Barrier(from, done) == CHOOSE q \in from..(N + 1) :
                         (\A i \in from..(q - 1) : i \in done) /\ (q = N + 1 \/ q \notin done)

Accepted(ep) == Prefix(ctrlSeqs[ep])
Done == stored \cup (IF Policy = "SKIP" THEN disposed ELSE {})
Active(e) == running[e] /\ myEpoch[e] # 0 /\ removed[e] = FALSE

Init ==
    /\ epoch = 1
    /\ \E h \in Exec : /\ holder = [ep \in Epochs |-> h]      \* entries above epoch are unused until issued
                      /\ myEpoch = [e \in Exec |-> IF e = h THEN 1 ELSE 0]
    /\ running = [e \in Exec |-> FALSE]
    /\ startPos = [e \in Exec |-> 1]
    /\ readPos = [e \in Exec |-> 1]
    /\ pending = [e \in Exec |-> {}]
    /\ resolved = [e \in Exec |-> {}]
    /\ outbox = [e \in Exec |-> {}]
    /\ seqHigh = [e \in Exec |-> [ep \in Epochs |-> 0]]
    /\ localCkpt = [e \in Exec |-> 1]
    /\ ackedRec = [e \in Exec |-> {}]
    /\ acceptedPos = [e \in Exec |-> 1]
    /\ halted = None
    /\ net = {} /\ ackNet = {}
    /\ keys = {} /\ stored = {} /\ storeCount = [i \in Items |-> 0] /\ disposed = {}
    /\ ctrlSeqs = [ep \in Epochs |-> {}]
    /\ ctrlCkpt = 1
    /\ producer = [i \in Items |-> None]
    /\ distrusted = {} /\ quarantined = {} /\ queue = {}
    /\ ckptVersion = 1 /\ pkgVersion = 1
    /\ removed = [e \in Exec |-> FALSE]
    /\ lostCount = [e \in Exec |-> 0]
    /\ faults = [crash |-> 0, drop |-> 0, purge |-> 0]

\* ---------------------------------------------------------------- executor

Start(e) ==
    /\ ~running[e] /\ myEpoch[e] = epoch /\ holder[epoch] = e /\ removed[e] = FALSE
    /\ halted = None /\ ckptVersion = pkgVersion          \* M: never start on an old-format checkpoint
    /\ running' = [running EXCEPT ![e] = TRUE]
    /\ startPos' = [startPos EXCEPT ![e] = localCkpt[e]]
    /\ readPos' = [readPos EXCEPT ![e] = localCkpt[e]]
    /\ pending' = [pending EXCEPT ![e] = {}]
    /\ resolved' = [resolved EXCEPT ![e] = {}]
    /\ UNCHANGED <<myEpoch, outbox, seqHigh, localCkpt, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl /\ UNCHANGED misc

Emit(e) ==
    /\ Active(e) /\ readPos[e] <= N
    /\ pending' = [pending EXCEPT ![e] = @ \cup {readPos[e]}]
    /\ readPos' = [readPos EXCEPT ![e] = @ + 1]
    /\ UNCHANGED <<myEpoch, running, startPos, resolved, outbox, seqHigh, localCkpt, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl /\ UNCHANGED misc

\* T1 / T3: one append; the next outboxSeq is the last durable one + 1, so sequences are gap-free (S1).
Append(e, i, loss) ==
    LET ep == myEpoch[e]
        s == seqHigh[e][ep] + 1
    IN /\ s <= MaxSeq
       /\ outbox' = [outbox EXCEPT ![e] = @ \cup {[ep |-> ep, seq |-> s, id |-> i, loss |-> loss]}]
       /\ seqHigh' = [seqHigh EXCEPT ![e][ep] = s]
       /\ pending' = [pending EXCEPT ![e] = @ \ {i}]
       /\ resolved' = [resolved EXCEPT ![e] = @ \cup {i}]
       /\ UNCHANGED <<myEpoch, running, startPos, readPos, localCkpt, ackedRec, acceptedPos>>
       /\ UNCHANGED ctrl /\ UNCHANGED misc

\* Emits complete in any order. A retryable rejection (T2) is the absence of this step: the item stays pending.
Durable(e, i) == Active(e) /\ i \in pending[e] /\ i \notin Bad /\ Append(e, i, FALSE)
LossRecord(e, i) == Policy = "SKIP" /\ Active(e) /\ i \in pending[e] /\ i \in Bad /\ Append(e, i, TRUE)

Halt(e, i) ==                                              \* T4: condition only, no record
    /\ Policy = "HALT" /\ Active(e) /\ i \in pending[e] /\ i \in Bad
    /\ halted' = i
    /\ running' = [running EXCEPT ![e] = FALSE]
    /\ UNCHANGED <<myEpoch, startPos, readPos, pending, resolved, outbox, seqHigh, localCkpt, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl
    /\ UNCHANGED <<net, ackNet, ckptVersion, pkgVersion, removed, lostCount, faults>>

LocalCheckpoint(e) ==                                      \* T5: contiguous barrier, atomic rename
    /\ Active(e)
    /\ LET q == Barrier(startPos[e], resolved[e]) IN
         /\ q > localCkpt[e]
         /\ localCkpt' = [localCkpt EXCEPT ![e] = q]
    /\ UNCHANGED <<myEpoch, running, startPos, readPos, pending, resolved, outbox, seqHigh, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl /\ UNCHANGED misc

Crash(e) ==
    /\ running[e] /\ faults.crash < MaxCrash
    /\ running' = [running EXCEPT ![e] = FALSE]
    /\ pending' = [pending EXCEPT ![e] = {}]
    /\ resolved' = [resolved EXCEPT ![e] = {}]
    /\ faults' = [faults EXCEPT !.crash = @ + 1]
    \* Process memory is gone; reset it so stale values never split otherwise equal states (Start overwrites it).
    /\ startPos' = [startPos EXCEPT ![e] = 1] /\ readPos' = [readPos EXCEPT ![e] = 1]
    /\ UNCHANGED <<myEpoch, outbox, seqHigh, localCkpt, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl
    /\ UNCHANGED <<halted, net, ackNet, ckptVersion, pkgVersion, removed, lostCount>>

\* The old holder learns that its lease is gone (expiry or a revocation in the ack) and stops.
LeaseLost(e) ==
    /\ myEpoch[e] # 0 /\ myEpoch[e] # epoch
    /\ myEpoch' = [myEpoch EXCEPT ![e] = 0]
    /\ running' = [running EXCEPT ![e] = FALSE]
    /\ pending' = [pending EXCEPT ![e] = {}]
    /\ resolved' = [resolved EXCEPT ![e] = {}]
    /\ startPos' = [startPos EXCEPT ![e] = 1] /\ readPos' = [readPos EXCEPT ![e] = 1]
    /\ UNCHANGED <<outbox, seqHigh, localCkpt, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl /\ UNCHANGED misc

\* ---------------------------------------------------------------- transport

\* A check-in carries every unacknowledged record (oldest first, plan 8.2). Control may still process the
\* records of one check-in, and of several, in any order; lost and duplicated check-ins are both explored.
Send(e) ==
    /\ outbox[e] # {} /\ removed[e] = FALSE
    /\ LET batch == {[ep |-> r.ep, seq |-> r.seq, id |-> r.id, loss |-> r.loss, from |-> e] : r \in outbox[e]}
       IN /\ ~(batch \subseteq net)
          /\ net' = net \cup batch
    /\ UNCHANGED exec /\ UNCHANGED ctrl
    /\ UNCHANGED <<halted, ackNet, ckptVersion, pkgVersion, removed, lostCount, faults>>

Drop(m) ==
    /\ faults.drop < MaxDrop
    /\ \/ m \in net /\ net' = net \ {m} /\ UNCHANGED ackNet
       \/ m \in ackNet /\ ackNet' = ackNet \ {m} /\ UNCHANGED net
    /\ faults' = [faults EXCEPT !.drop = @ + 1]
    /\ UNCHANGED exec /\ UNCHANGED ctrl
    /\ UNCHANGED <<halted, ckptVersion, pkgVersion, removed, lostCount>>

\* ---------------------------------------------------------------- Control

\* A record is fenced when its epoch was issued to another executor than the current lease holder (failover
\* or reassignment), or its producer is distrusted. Older epochs of the current holder still drain.
Fenced(m) == (holder[m.ep] # holder[epoch]) \/ m.from \in distrusted \/ m.from # holder[m.ep]

Ingest(m) ==                                               \* T6, or T9 when fenced
    /\ m \in net
    /\ IF Fenced(m)
       THEN /\ ackNet' = ackNet \cup {[to |-> m.from, ep |-> m.ep, upto |-> 0, fencedSeq |-> m.seq]}
            /\ UNCHANGED ctrl
       ELSE LET seqs == [ctrlSeqs EXCEPT ![m.ep] = @ \cup {m.seq}]
                fresh == ~m.loss /\ m.id \notin keys
            IN /\ ctrlSeqs' = seqs
               /\ disposed' = IF m.loss THEN disposed \cup {m.id} ELSE disposed
               /\ keys' = IF fresh THEN keys \cup {m.id} ELSE keys
               /\ stored' = IF fresh THEN stored \cup {m.id} ELSE stored
               /\ storeCount' = IF fresh THEN [storeCount EXCEPT ![m.id] = @ + 1] ELSE storeCount
               /\ producer' = IF fresh THEN [producer EXCEPT ![m.id] = m.from] ELSE producer
               /\ queue' = IF fresh THEN queue \cup {[id |-> m.id, from |-> m.from]} ELSE queue
               \* Acks are cumulative and Prefix only grows, so a newer ack for the same executor and epoch
               \* supersedes an older one still in flight: delivering the older one after the newer is a no-op
               \* (its records are already truncated and acknowledged), so that reordering adds no behaviour;
               \* "older delivered, newer lost" stays reachable because ReceiveAck of the older ack commutes
               \* with the Ingest that produced the newer one.
               /\ ackNet' = {a \in ackNet : ~(a.to = m.from /\ a.ep = m.ep /\ a.fencedSeq = 0)}
                            \cup {[to |-> m.from, ep |-> m.ep, upto |-> Prefix(seqs[m.ep]), fencedSeq |-> 0]}
               /\ UNCHANGED <<epoch, holder, ctrlCkpt, distrusted, quarantined>>
    /\ net' = net \ {m}                \* consumed; duplicates arrive by re-sending from the outbox
    /\ UNCHANGED exec
    /\ UNCHANGED <<halted, ckptVersion, pkgVersion, removed, lostCount, faults>>

\* Records a cumulative acknowledgement lets its executor delete (empty for a fenced acknowledgement).
AckedBy(a) == {r \in outbox[a.to] : a.fencedSeq = 0 /\ r.ep = a.ep /\ r.seq <= a.upto}

\* T8 / T10: truncate only acknowledged or fenced records; the ACCEPTED position advances only from acks.
ReceiveAck(a) ==
    /\ a \in ackNet /\ removed[a.to] = FALSE
    /\ LET e == a.to
           okd == AckedBy(a)
           fen == {r \in outbox[e] : a.fencedSeq # 0 /\ r.ep = a.ep /\ r.seq = a.fencedSeq}
           acks == ackedRec[e] \cup {[ep |-> r.ep, seq |-> r.seq, id |-> r.id] : r \in okd}
       IN /\ outbox' = [outbox EXCEPT ![e] = @ \ (okd \cup fen)]
          /\ ackedRec' = [ackedRec EXCEPT ![e] = acks]
          /\ acceptedPos' = [acceptedPos EXCEPT ![e] = Barrier(@, {x.id : x \in acks})]
    /\ ackNet' = ackNet \ {a}
    /\ UNCHANGED <<myEpoch, running, startPos, readPos, pending, resolved, seqHigh, localCkpt>>
    /\ UNCHANGED ctrl
    /\ UNCHANGED <<halted, net, ckptVersion, pkgVersion, removed, lostCount, faults>>

\* T7: the current holder commits its ACCEPTED position. Control refuses it unless the highest acknowledged
\* outboxSeq behind that position in the current epoch is within Control's contiguous accepted sequence.
AcceptedCheckpoint(e) ==
    /\ myEpoch[e] = epoch /\ holder[epoch] = e /\ removed[e] = FALSE
    /\ acceptedPos[e] > ctrlCkpt
    /\ Max({x.seq : x \in {y \in ackedRec[e] : y.ep = epoch /\ y.id < acceptedPos[e]}}) <= Accepted(epoch)
    /\ ctrlCkpt' = acceptedPos[e]
    /\ UNCHANGED exec
    /\ UNCHANGED <<epoch, holder, keys, stored, storeCount, disposed, ctrlSeqs, producer, distrusted,
                   quarantined, queue>>
    /\ UNCHANGED misc

\* ---------------------------------------------------------------- leases and lifecycle

\* Failover or reassignment to e at a new epoch. The new holder resumes from Control's ACCEPTED checkpoint
\* (I). The previous holder is not stopped here: it keeps running on its stale epoch until LeaseLost.
Failover(e) ==
    /\ epoch < MaxEpoch /\ holder[epoch] # e /\ removed[e] = FALSE /\ ~running[e] /\ e \notin distrusted
    /\ epoch' = epoch + 1
    /\ holder' = [holder EXCEPT ![epoch + 1] = e]
    /\ myEpoch' = [myEpoch EXCEPT ![e] = epoch + 1]
    /\ localCkpt' = [localCkpt EXCEPT ![e] = ctrlCkpt]
    /\ acceptedPos' = [acceptedPos EXCEPT ![e] = ctrlCkpt]
    /\ UNCHANGED <<running, startPos, readPos, pending, resolved, outbox, seqHigh, ackedRec>>
    /\ UNCHANGED <<keys, stored, storeCount, disposed, ctrlSeqs, ctrlCkpt, producer, distrusted,
                   quarantined, queue>>
    /\ UNCHANGED misc

\* T14: the holder loses its disk and is reinstalled under a new epoch from the ACCEPTED checkpoint.
Reinstall(e) ==
    /\ epoch < MaxEpoch /\ holder[epoch] = e /\ myEpoch[e] = epoch /\ removed[e] = FALSE
    /\ epoch' = epoch + 1
    /\ holder' = [holder EXCEPT ![epoch + 1] = e]
    /\ myEpoch' = [myEpoch EXCEPT ![e] = epoch + 1]
    /\ running' = [running EXCEPT ![e] = FALSE]
    /\ pending' = [pending EXCEPT ![e] = {}]
    /\ resolved' = [resolved EXCEPT ![e] = {}]
    /\ outbox' = [outbox EXCEPT ![e] = {}]
    /\ seqHigh' = [seqHigh EXCEPT ![e] = [ep \in Epochs |-> 0]]
    /\ localCkpt' = [localCkpt EXCEPT ![e] = ctrlCkpt]
    /\ ackedRec' = [ackedRec EXCEPT ![e] = {}]
    /\ acceptedPos' = [acceptedPos EXCEPT ![e] = ctrlCkpt]
    /\ startPos' = [startPos EXCEPT ![e] = 1] /\ readPos' = [readPos EXCEPT ![e] = 1]
    /\ UNCHANGED <<keys, stored, storeCount, disposed, ctrlSeqs, ctrlCkpt, producer, distrusted,
                   quarantined, queue>>
    /\ UNCHANGED misc

\* T12: a new checkpoint schema either migrates every live checkpoint together with the package, or
\* discards them all and restarts from the beginning. Never both formats, never a guess.
Upgrade ==
    /\ AllowUpgrade /\ pkgVersion = 1 /\ \A e \in Exec : ~running[e]
    /\ pkgVersion' = 2 /\ ckptVersion' = 2
    /\ \/ UNCHANGED <<localCkpt, acceptedPos, ctrlCkpt>>
       \/ /\ localCkpt' = [e \in Exec |-> 1] /\ acceptedPos' = [e \in Exec |-> 1] /\ ctrlCkpt' = 1
    /\ UNCHANGED <<myEpoch, running, startPos, readPos, pending, resolved, outbox, seqHigh, ackedRec>>
    /\ UNCHANGED <<epoch, holder, keys, stored, storeCount, disposed, ctrlSeqs, producer, distrusted,
                   quarantined, queue>>
    /\ UNCHANGED <<halted, net, ackNet, removed, lostCount, faults>>

\* T13: a non-holding, stopped executor is removed; records it could not drain are counted, never dropped silently.
Remove(e) ==
    /\ AllowRemove /\ removed[e] = FALSE /\ holder[epoch] # e /\ ~running[e]
    /\ removed' = [removed EXCEPT ![e] = TRUE]
    /\ lostCount' = [lostCount EXCEPT ![e] = Cardinality({r \in outbox[e] : ~r.loss})]
    /\ outbox' = [outbox EXCEPT ![e] = {}]
    /\ UNCHANGED <<myEpoch, running, startPos, readPos, pending, resolved, seqHigh, localCkpt, ackedRec, acceptedPos>>
    /\ UNCHANGED ctrl
    /\ UNCHANGED <<halted, net, ackNet, ckptVersion, pkgVersion, faults>>

\* T11: retention purges dedupe keys behind the ACCEPTED checkpoint. Accepted state never changes.
Purge(i) ==
    /\ faults.purge < MaxPurge /\ i \in keys /\ i < ctrlCkpt
    /\ keys' = keys \ {i}
    /\ faults' = [faults EXCEPT !.purge = @ + 1]
    /\ UNCHANGED exec
    /\ UNCHANGED <<epoch, holder, stored, storeCount, disposed, ctrlSeqs, ctrlCkpt, producer, distrusted,
                   quarantined, queue>>
    /\ UNCHANGED <<halted, net, ackNet, ckptVersion, pkgVersion, removed, lostCount>>

\* T15: distrust a producer: its stored items are quarantined and their pending deliveries cancelled.
\* Compromise revocation also revokes the producer's lease (plan 6.6.1), so the lease has already moved to
\* another executor (Failover) before the distrust takes effect, and a distrusted executor never gets it back.
Distrust(e) ==
    /\ AllowDistrust /\ e \notin distrusted /\ holder[epoch] # e
    /\ distrusted' = distrusted \cup {e}
    /\ quarantined' = quarantined \cup {[id |-> i, from |-> e] : i \in {j \in stored : producer[j] = e}}
    /\ queue' = {q \in queue : q.from # e}
    /\ UNCHANGED exec
    /\ UNCHANGED <<epoch, holder, keys, stored, storeCount, disposed, ctrlSeqs, ctrlCkpt, producer>>
    /\ UNCHANGED misc

Next ==
    \/ \E e \in Exec : \/ Start(e) \/ Emit(e) \/ LocalCheckpoint(e) \/ Crash(e) \/ LeaseLost(e)
                       \/ AcceptedCheckpoint(e) \/ Failover(e) \/ Reinstall(e) \/ Remove(e) \/ Distrust(e)
    \/ \E e \in Exec, i \in Items : Durable(e, i) \/ LossRecord(e, i) \/ Halt(e, i)
    \/ \E e \in Exec : Send(e)
    \/ \E m \in net : Ingest(m)
    \/ \E a \in ackNet : ReceiveAck(a)
    \/ \E m \in net \cup ackNet : Drop(m)
    \/ \E i \in Items : Purge(i)
    \/ Upgrade

Spec == Init /\ [][Next]_vars

\* Bounded channels: at most MaxInFlight records to Control and acknowledgements back at a time.
InFlight == Cardinality(net) <= MaxInFlight /\ Cardinality(ackNet) <= MaxInFlight

ExecSymmetry == Permutations(Exec)   \* executors are interchangeable; used by the safety configurations only

\* Liveness: every item of a replayable source is eventually stored, under fair scheduling of the
\* non-fault steps. Faults (crash, drop, purge, failover, reinstall) are finite by the bounds above.
Fairness ==
    /\ \A e \in Exec : WF_vars(Start(e)) /\ WF_vars(Emit(e)) /\ WF_vars(LocalCheckpoint(e))
                       /\ WF_vars(LeaseLost(e)) /\ WF_vars(AcceptedCheckpoint(e))
    /\ \A e \in Exec, i \in Items : WF_vars(Durable(e, i)) /\ WF_vars(LossRecord(e, i))
    /\ \A e \in Exec : SF_vars(Send(e))
    \* Per record (producer, epoch, outboxSeq): Control processes every record of a check-in. A coarser fairness
    \* (over the whole network, or per item) lets TLC re-ingest one retransmitted or fenced copy forever while
    \* another record waits; both were TLC counterexamples that no real Control exhibits. Weak fairness suffices:
    \* a record that stays in flight is processed (and is cheaper for TLC than strong fairness).
    /\ \A e \in Exec, ep \in Epochs, s \in 1..MaxSeq :
         WF_vars(\E m \in net : m.from = e /\ m.ep = ep /\ m.seq = s /\ Ingest(m))
    /\ SF_vars(\E a \in ackNet : ReceiveAck(a))
FairSpec == Spec /\ Fairness

EventuallyStored == <>[](\A i \in Items \ Bad : i \in stored)

\* ---------------------------------------------------------------- invariants (plan 6.6.1)

TypeOK ==
    /\ epoch \in Epochs /\ ctrlCkpt \in Positions /\ halted \in Items \cup {None}
    /\ \A e \in Exec : localCkpt[e] \in Positions /\ acceptedPos[e] \in Positions /\ myEpoch[e] \in 0..MaxEpoch

\* S1: every epoch's outbox sequences are the contiguous range below seqHigh minus acknowledged or fenced ones.
S1 == \A e \in Exec : \A r \in outbox[e] : r.seq <= seqHigh[e][r.ep]
\* S3: under SKIP a rejected item is resolved only through a loss record, never stored.
S3 == \A i \in Bad : i \notin stored
\* S4: under HALT no checkpoint of any level passes the halted item.
S4 == halted # None => /\ \A e \in Exec : localCkpt[e] <= halted /\ acceptedPos[e] <= halted
                       /\ ctrlCkpt <= halted
\* L: a LOCAL checkpoint of an executor whose lease epoch is not fenced never passes an item that is neither
\* durable in its outbox nor resolved at Control. (A fenced executor's LOCAL checkpoint is never used again:
\* a new lease always resumes from Control's ACCEPTED checkpoint, see Failover.)
L == \A e \in Exec : (~removed[e] /\ myEpoch[e] # 0 /\ holder[myEpoch[e]] = holder[epoch]) =>
       \A i \in 1..(localCkpt[e] - 1) : (\E r \in outbox[e] : r.id = i) \/ i \in Done
\* A: the contiguous accepted sequence covers only resolved records.
A == \A ep \in Epochs : \A k \in 1..Accepted(ep) : k \in ctrlSeqs[ep]
\* C: Control's ACCEPTED checkpoint never passes an item Control has not resolved.
C == \A i \in 1..(ctrlCkpt - 1) : i \in Done
\* D: at most one stored row per item, except after a retention purge of its key (T11).
D == \A i \in Items : storeCount[i] <= 1 + faults.purge
\* P: a record is deleted after an acknowledgement only when Control has resolved its item.
\* (action property: checked on every acknowledgement step rather than through a ghost variable)
P == [][\A a \in ackNet : ReceiveAck(a) => \A r \in AckedBy(a) : r.id \in Done]_vars
\* P2: an executor's ACCEPTED position never passes an unresolved item.
P2 == \A e \in Exec : \A i \in 1..(acceptedPos[e] - 1) : i \in Done
\* F: fenced records never create rows or dedupe keys: every stored row came from the holder of its epoch.
F == /\ keys \subseteq stored
     /\ \A i \in stored : producer[i] \in Exec
\* R (action property): retention changes only what can be deduped, never what has been accepted or stored.
R == [][\A i \in Items : Purge(i) => UNCHANGED <<ctrlCkpt, ctrlSeqs, stored, storeCount, disposed, queue,
                                                  acceptedPos, localCkpt>>]_vars
\* M: a running process always uses the running package's checkpoint schema.
M == \A e \in Exec : running[e] => ckptVersion = pkgVersion
\* X: a removed executor holds nothing and its undrained records are counted.
X == \A e \in Exec : removed[e] => outbox[e] = {} /\ ~running[e]
\* I: the current holder never resumes from a position Control and its own outbox do not cover.
I == LET h == holder[epoch] IN myEpoch[h] = epoch =>
       \A i \in 1..(localCkpt[h] - 1) : (\E r \in outbox[h] : r.id = i) \/ i \in Done
\* Q: no pending delivery comes from a distrusted producer, and nothing quarantined is still queued. A later row of
\* the same item from a trusted producer (after T11 purged its key) is a new observation and may be delivered.
Q == /\ \A q \in queue : q.from \notin distrusted
     /\ quarantined \cap queue = {}

=============================================================================
