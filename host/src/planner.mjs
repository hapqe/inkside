/**
 * What to learn next, worked out from one project's learner model.
 *
 * Three kinds of work compete for the session:
 *
 *   - Reviews: topics already met come back after a spacing interval that grows as
 *     they climb the ladder (explained → … → mastered) and with every time the student
 *     has shown them again at that level. A topic is never reviewed before it is due
 *     (that wastes the spacing), and the most overdue — relative to its interval —
 *     comes first. A review also asks for the next rung: recalling an "explained"
 *     topic, applying a "recalled" one, and so on.
 *   - Goal work: open tasks from the project's documents. A task whose topics have
 *     not been met yet is not started; its missing topics are learned first.
 *   - New topics: prerequisites of open goals that are still "not encountered".
 *
 * The session is as long as the weekly time goal still needs per remaining day, or a
 * default without one. Reviews go first (short retrieval warms up), then goal work and
 * new topics alternate, never more than two new topics a session and never two items
 * from the same document back to back when another can go between them.
 */
import { LEVELS, conceptKey } from "./learning.mjs";

const DAY_MS = 24 * 3600 * 1000;

/** Days until a topic at each level comes back, the first time it is there. */
const BASE_INTERVAL_DAYS = {
  explained: 1,
  recalled: 2,
  applied: 4,
  transferred: 8,
  mastered: 21,
};
/** Each further showing at the same level stretches the interval by this much. */
const INTERVAL_GROWTH = 1.8;
const MAX_INTERVAL_DAYS = 90;

/** Minutes each kind of item is planned for. */
const MINUTES = { review: 5, reviewMastered: 3, learn: 15, goal: 20, continue: 10 };
const MAX_NEW_PER_SESSION = 2;
const DEFAULT_SESSION_MIN = 30;
const MIN_SESSION_MIN = 15;
const MAX_SESSION_MIN = 120;

/** What a review of a topic at this level asks of the student: the next rung. */
const REVIEW_ASK = {
  explained: "Recall it: explain it in your own words, without notes.",
  recalled: "Apply it: solve a short problem that needs it.",
  applied: "Transfer it: use it in a situation you have not seen it in.",
  transferred: "Show mastery: a harder transfer question, no help.",
  mastered: "Quick check that it is still there.",
};

const levelIndex = (l) => Math.max(0, LEVELS.indexOf(String(l || "")));
const time = (iso) => {
  const t = Date.parse(iso || "");
  return Number.isFinite(t) ? t : null;
};

/**
 * When a topic is next due and how sure that is.
 *
 * The interval comes from its level and from how many times in a row (most recent
 * first) the student has shown it at that level; a slip back down starts the count
 * again. Practice is the latest evidence of any kind.
 */
export function reviewSchedule(concept, now = Date.now()) {
  const level = concept?.level || "not_encountered";
  if (level === "not_encountered") return null;
  const evidence = Array.isArray(concept.evidence) ? concept.evidence : [];
  const lastAt = evidence.reduce((m, e) => Math.max(m, time(e.at) || 0), 0) || time(concept.updatedAt) || now;
  const li = levelIndex(level);
  let streak = 0;
  for (let i = evidence.length - 1; i >= 0; i--) {
    const e = evidence[i];
    // An explanation says nothing about a level the student already showed.
    if (e.source !== "student" && li > levelIndex("explained")) continue;
    // Only showings at this very level count: one from higher up means it slipped.
    if (levelIndex(e.level) === li) streak++;
    else break;
  }
  const base = BASE_INTERVAL_DAYS[level] || 1;
  const days = Math.min(MAX_INTERVAL_DAYS, base * Math.pow(INTERVAL_GROWTH, Math.max(0, streak - 1)));
  const intervalMs = days * DAY_MS;
  const dueAt = lastAt + intervalMs;
  return {
    lastPracticedAt: new Date(lastAt).toISOString(),
    intervalDays: Math.round(days * 10) / 10,
    dueAt: new Date(dueAt).toISOString(),
    /** 1 = due now; 2 = a whole interval late. */
    overdue: (now - lastAt) / intervalMs,
  };
}

/**
 * Today's study time: what is still missing from the weekly goal spread over the days
 * left (today included), within sensible bounds. Without a goal, a default session.
 */
export function sessionMinutes({ weeklyGoalMinutes = 0, studiedMinutes = 0, daysLeft = 7 } = {}) {
  const goal = Math.max(0, Number(weeklyGoalMinutes) || 0);
  if (!goal) return { minutes: DEFAULT_SESSION_MIN, remainingWeek: null, perDay: null };
  const remaining = Math.max(0, goal - Math.max(0, Number(studiedMinutes) || 0));
  const days = Math.max(1, Math.min(7, Math.round(Number(daysLeft) || 1)));
  const perDay = remaining / days;
  const minutes = remaining <= 0 ? 0 : Math.round(Math.min(MAX_SESSION_MIN, Math.max(MIN_SESSION_MIN, perDay)));
  return { minutes, remainingWeek: Math.round(remaining), perDay: Math.round(perDay) };
}

/**
 * The plan for the next session.
 *
 * @param state one project's learner snapshot (concepts, goals, weeklyGoalMinutes)
 * @param opts.studiedMinutes  time studied in this project this week (the app's log)
 * @param opts.daysLeft        days left in the week, today included
 * @param opts.sessionMinutes  plan this long instead of what the weekly goal asks
 * @param opts.now             epoch ms
 */
export function planNext(state, { studiedMinutes = 0, daysLeft = 7, sessionMinutes: fixed = null, now = Date.now() } = {}) {
  const weeklyGoalMinutes = Math.max(0, Number(state?.weeklyGoalMinutes) || 0);
  const budget = sessionMinutes({ weeklyGoalMinutes, studiedMinutes, daysLeft });
  const target = Number(fixed) > 0 ? Math.round(Number(fixed)) : budget.minutes || DEFAULT_SESSION_MIN;

  // Topics: recorded ones plus every one an open goal names.
  const concepts = new Map();
  for (const [key, c] of Object.entries(state?.concepts || {})) concepts.set(key, c);
  const goalsByConcept = new Map();
  const openGoals = [];
  for (const [doc, g] of Object.entries(state?.goals || {})) {
    for (const it of g?.items || []) {
      if (it.status !== "open") continue;
      openGoals.push({ doc, goal: it });
      for (const name of it.concepts || []) {
        const key = conceptKey(name);
        if (!key) continue;
        if (!concepts.has(key)) concepts.set(key, { name, level: "not_encountered", evidence: [] });
        if (!goalsByConcept.has(key)) goalsByConcept.set(key, []);
        goalsByConcept.get(key).push({ doc, id: it.id, task: it.task });
      }
    }
  }

  // Reviews that are due, most overdue first; topics an open goal needs a little ahead.
  const reviews = [];
  const schedule = [];
  for (const [key, c] of concepts) {
    const sch = reviewSchedule(c, now);
    if (!sch) continue;
    const needs = goalsByConcept.get(key) || [];
    schedule.push({ concept: c.name, level: c.level, ...sch });
    if (sch.overdue < 1) continue;
    reviews.push({
      kind: "review",
      concept: c.name,
      level: c.level,
      document: needs[0]?.doc || null,
      minutes: c.level === "mastered" ? MINUTES.reviewMastered : MINUTES.review,
      score: sch.overdue * (needs.length ? 1.25 : 1),
      title: `Review: ${c.name}`,
      ask: REVIEW_ASK[c.level] || REVIEW_ASK.explained,
      reason: dueReason(sch, now) + (needs.length ? `; needed for ${needs.length === 1 ? needs[0].task : `${needs.length} open goals`}` : ""),
    });
  }
  reviews.sort((a, b) => b.score - a.score);
  schedule.sort((a, b) => Date.parse(a.dueAt) - Date.parse(b.dueAt));

  // Goals: ones already under way first, then those whose topics have all been met.
  // A goal with unmet topics turns into learning those topics.
  const goalItems = [];
  const learnWanted = new Map();
  for (const { doc, goal } of openGoals) {
    const keys = (goal.concepts || []).map(conceptKey).filter(Boolean);
    const unmet = keys.filter((k) => levelIndex(concepts.get(k)?.level) < levelIndex("explained"));
    const ready = keys.length
      ? keys.reduce((s, k) => s + Math.min(1, levelIndex(concepts.get(k)?.level) / levelIndex("applied")), 0) / keys.length
      : 0.5;
    if (goal.awaitingStudent || goal.hintLevel > 0) {
      goalItems.push({
        kind: "goal",
        document: doc,
        goalId: goal.id,
        minutes: MINUTES.continue,
        score: 3 + ready,
        title: `Continue: ${goal.task}`,
        ask: goal.awaitingStudent
          ? "Your turn: try the next step from the last hint yourself."
          : "Pick up where you left off.",
        reason: `Started (hint ${goal.hintLevel}/5)${goal.awaitingStudent ? ", waiting on your next step" : ""}`,
      });
      continue;
    }
    if (unmet.length) {
      for (const k of unmet) {
        const prev = learnWanted.get(k);
        if (prev) prev.goals.push(goal.task);
        else learnWanted.set(k, { key: k, doc, goals: [goal.task] });
      }
      continue;
    }
    goalItems.push({
      kind: "goal",
      document: doc,
      goalId: goal.id,
      minutes: MINUTES.goal,
      score: 1 + ready,
      title: `Work on: ${goal.task}`,
      ask: goal.objective || "Solve it yourself; ask for a hint only when stuck.",
      reason: keys.length ? `Its topics are in place (${Math.round(ready * 100)}% ready)` : "Open goal",
    });
  }
  goalItems.sort((a, b) => b.score - a.score);

  // New topics: the ones blocking the most goals first.
  const learnItems = [...learnWanted.values()]
    .sort((a, b) => b.goals.length - a.goals.length)
    .map((w) => {
      const c = concepts.get(w.key);
      return {
        kind: "learn",
        concept: c?.name || w.key,
        level: "not_encountered",
        document: w.doc,
        minutes: MINUTES.learn,
        score: 1.5 + 0.2 * Math.min(5, w.goals.length),
        title: `Learn: ${c?.name || w.key}`,
        ask: "Learn it with the tutor, then explain it back in your own words.",
        reason: `Needed for ${w.goals.length === 1 ? w.goals[0] : `${w.goals.length} goals (${w.goals.slice(0, 2).join(", ")}…)`}`,
      };
    });

  // Fill the session.
  const items = [];
  let used = 0;
  const fits = (it) => used + it.minutes <= target || items.length === 0;
  const take = (it) => {
    items.push(it);
    used += it.minutes;
  };
  // Due reviews first, but leave room for real work when there is some.
  const mainWork = goalItems.length + learnItems.length > 0;
  const reviewCap = mainWork ? Math.max(MINUTES.review, Math.round(target * 0.4)) : target;
  for (const r of reviews) {
    if (used + r.minutes > reviewCap && items.length) break;
    if (!fits(r)) break;
    take(r);
  }
  // Then goal work and new topics, alternating, keeping one document from running on.
  const queues = [goalItems.slice(), learnItems.slice()];
  let newCount = 0;
  let turn = goalItems.length && goalItems[0].score >= 3 ? 0 : learnItems.length ? 1 : 0;
  while (queues[0].length || queues[1].length) {
    let q = queues[turn].length ? turn : 1 - turn;
    if (q === 1 && newCount >= MAX_NEW_PER_SESSION) {
      queues[1] = [];
      if (!queues[0].length) break;
      q = 0;
    }
    const last = items[items.length - 1];
    let idx = queues[q].findIndex((it) => !last || !it.document || it.document !== last.document);
    if (idx < 0) idx = 0;
    const it = queues[q][idx];
    if (!fits(it)) {
      queues[q].splice(idx, 1);
      if (!queues[0].length && !queues[1].length) break;
      continue;
    }
    queues[q].splice(idx, 1);
    take(it);
    if (q === 1) newCount++;
    turn = 1 - q;
  }
  // Time left over and reviews still due: fit in what goes.
  for (const r of reviews) {
    if (items.includes(r)) continue;
    if (used + r.minutes <= target) take(r);
  }

  const nextReview = schedule.find((s) => Date.parse(s.dueAt) > now) || null;
  return {
    weeklyGoalMinutes,
    studiedMinutes: Math.round(Math.max(0, Number(studiedMinutes) || 0)),
    daysLeft: Math.max(1, Math.round(Number(daysLeft) || 1)),
    remainingWeekMinutes: budget.remainingWeek,
    perDayMinutes: budget.perDay,
    sessionMinutes: target,
    plannedMinutes: used,
    weekDone: weeklyGoalMinutes > 0 && budget.remainingWeek === 0,
    // How pressing each item is, comparable across projects (see pickNext).
    items: items.map(({ score, ...it }) => ({
      ...it,
      priority: Math.round((it.kind === "review" ? 1.5 + Math.min(2, score - 1) : score) * 100) / 100,
    })),
    dueReviews: reviews.length,
    nextReviewAt: nextReview ? nextReview.dueAt : null,
    nextReviewConcept: nextReview ? nextReview.concept : null,
  };
}

function dueReason(sch, now) {
  const late = (now - Date.parse(sch.dueAt)) / DAY_MS;
  const last = Math.round((now - Date.parse(sch.lastPracticedAt)) / DAY_MS);
  const ago = last <= 0 ? "today" : last === 1 ? "yesterday" : `${last} days ago`;
  if (late < 0.5) return `Due now (last practised ${ago})`;
  const d = Math.round(late);
  return `Overdue by ${d <= 1 ? "a day" : `${d} days`} (last practised ${ago})`;
}

/** A few lines for the tutor's prompt: what the planner would do next. */
export function formatPlanForPrompt(plan, limit = 4) {
  if (!plan?.items?.length) return null;
  const lines = ["", "Study plan (spaced; follow it when the student asks what to do or has no task of their own):"];
  for (const it of plan.items.slice(0, limit)) {
    lines.push(`- ${it.title} — ${it.ask} [${it.reason}${it.document ? `; ${it.document}` : ""}]`);
  }
  if (plan.weeklyGoalMinutes) {
    lines.push(`Weekly goal: ${Math.round(plan.weeklyGoalMinutes / 6) / 10} h in this project.`);
  }
  return lines.join("\n");
}

/**
 * The one thing to do next across several projects' plans ([{ project, plan }]).
 *
 * Each project's first item competes on its priority, scaled by how far behind its
 * weekly goal the project is: a project that needs more than its even share per
 * remaining day counts up to twice as much, one whose week is done half as much.
 * Projects without a goal count as on pace.
 */
export function pickNext(entries) {
  const ranked = [];
  for (const { project, plan } of entries || []) {
    const item = plan?.items?.[0];
    if (!item) continue;
    let pace = 1;
    if (plan.weeklyGoalMinutes > 0) {
      if (plan.weekDone) pace = 0.5;
      else {
        const share = plan.weeklyGoalMinutes / 7;
        pace = Math.max(0.5, Math.min(2, (plan.perDayMinutes || 0) / Math.max(1, share)));
      }
    }
    ranked.push({ project, item, pace, score: (item.priority || 1) * pace, plan });
  }
  ranked.sort((a, b) => b.score - a.score);
  return ranked;
}
