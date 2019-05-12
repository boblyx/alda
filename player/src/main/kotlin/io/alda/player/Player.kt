package io.alda.player

import com.illposed.osc.OSCMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

val playerQueue = LinkedBlockingQueue<List<OSCMessage>>()

val midi = MidiEngine()

val availableChannels = ((0..15).toSet() - 9).toMutableSet()

class Track(val trackNumber : Int) {
  private var _midiChannel : Int? = null
  fun midiChannel() : Int? {
    synchronized(availableChannels) {
      if (_midiChannel == null && !availableChannels.isEmpty()) {
        val channel = availableChannels.first()
        availableChannels.remove(channel)
        _midiChannel = channel
      }
    }

    return _midiChannel
  }

  fun useMidiPercussionChannel() { _midiChannel = 9 }

  val eventBufferQueue = LinkedBlockingQueue<List<Event>>()

  fun scheduleMidiPatch (event : MidiPatchEvent, startOffset : Int) {
    midiChannel()?.also { channel ->
      // debug
      println("track ${trackNumber} is channel ${channel}")
      midi.patch(startOffset + event.offset, channel, event.patch)
    } ?: run {
      println("WARN: No MIDI channel available for track ${trackNumber}.")
    }
  }

  fun scheduleMidiNote(event : MidiNoteEvent, startOffset : Int) {
    midiChannel()?.also { channel ->
      val noteStart = startOffset + event.offset
      val noteEnd = noteStart + event.audibleDuration
      midi.note(noteStart, noteEnd, channel, event.noteNumber, event.velocity)
    } ?: run {
      println("WARN: No MIDI channel available for track ${trackNumber}.")
    }
  }

  // Schedules the notes of a pattern in a "just in time" manner.
  //
  // Once all notes have been scheduled, returns the list of scheduled notes.
  fun schedulePattern(
    // An event that specifies a pattern, a relative offset where it should
    // begin, and a number of times to play it.
    event : PatternEvent,
    // The absolute offset to which the relative offset is added.
    startOffset : Int
  ) : List<MidiNoteEvent> {
    val patternStart = startOffset + event.offset

    // This value is the point in time where we schedule the metamessage that
    // signals the lookup and scheduling of the pattern's events.  This
    // scheduling happens shortly before the pattern is to be played.
    val patternSchedule = Math.max(
      startOffset, patternStart - SCHEDULE_BUFFER_TIME_MS
    )

    // This returns a CountDownLatch that starts at 1 and counts down to 0 when
    // the pattern metamessage is reached in the sequence.
    val latch = midi.pattern(patternSchedule, event.patternName)

    // Wait until it's time to look up the pattern's current value and
    // schedule the events.
    println("awaiting latch")
    latch.await()

    println("scheduling pattern ${event.patternName}")

    val pattern = pattern(event.patternName)

    val patternNoteEvents : MutableList<MidiNoteEvent> =
      (pattern.events.filter { it is MidiNoteEvent }
       as MutableList<MidiNoteEvent>)
      .map { it.addOffset(patternStart) } as MutableList<MidiNoteEvent>

    patternNoteEvents.forEach { scheduleMidiNote(it, 0) }

    val patternEvents =
      pattern.events.filter { it is PatternEvent }
      as List<PatternEvent>

    // Here, we handle the case where the pattern's events include further
    // pattern events, i.e. the pattern references another pattern.
    //
    // When a subpattern's events are scheduled, they are added to this
    // pattern's note events (`patternNoteEvents`).
    //
    // NB: Because of the "just in time" semantics of scheduling patterns, this
    // means we block here until the pattern is about due to be played.

    patternEvents.forEach { event ->
      patternNoteEvents.addAll(
        schedulePattern(event as PatternEvent, patternStart)
      )
    }

    // If the pattern event is to be played more than once, then we schedule the
    // next iteration of the pattern.
    //
    // NB: Because of the "just in time" semantics of scheduling patterns, this
    // means we block here until the next iteration is about due to be played.
    //
    // TODO: Use a loop instead of recursion? I could see this potentially
    // causing a StackOverflow if the pattern is to be repeated enough times.
    if (event.times > 1) {
      val nextStartOffset =
        (pattern.events.filter { it is MidiNoteEvent }
         as MutableList<MidiNoteEvent>)
        .map { it.offset + it.duration }.max()!!

      println("scheduling next iteration (event.times == ${event.times})")

      patternNoteEvents.addAll(
        schedulePattern(
          PatternEvent(nextStartOffset, event.patternName, event.times - 1),
          patternStart
        )
      )
    }

    return patternNoteEvents
  }

  fun scheduleEvents(events : List<Event>, _startOffset : Int) : Int {
    var startOffset = _startOffset

    val now = Math.round(midi.currentOffset()).toInt()

    // If we're not scheduling into the future, then the notes should be played
    // ASAP.
    if (startOffset < now) startOffset = now

    // Ensure that there is time to schedule the notes before it's time to play
    // them.
    if (midi.isPlaying && (startOffset - now < SCHEDULE_BUFFER_TIME_MS))
    startOffset += SCHEDULE_BUFFER_TIME_MS

    events.filter { it is MidiPatchEvent }.forEach {
      scheduleMidiPatch(it as MidiPatchEvent, startOffset)
    }

    events.filter { it is MidiPercussionEvent }.forEach {
      midi.percussion(
        startOffset + (it as MidiPercussionEvent).offset, trackNumber
      )
    }

    val noteEvents =
      (events.filter { it is MidiNoteEvent } as MutableList<MidiNoteEvent>)
        .map { it.addOffset(startOffset) } as MutableList<MidiNoteEvent>

    val patternEvents =
      events.filter { it is PatternEvent } as MutableList<PatternEvent>

    events.forEach { event ->
      when (event) {
        is PatternLoopEvent -> {
          // TODO
        }

        is FinishLoopEvent -> {
          // TODO
        }
      }
    }

    noteEvents.forEach { scheduleMidiNote(it, 0) }

    // Patterns can include other patterns, and to support dynamically changing
    // pattern contents on the fly, we look up each pattern's contents shortly
    // before it is scheduled to play. This means that the total number of
    // patterns can change at a moment's notice.

    // For each pattern event, we...
    // * wait until right before the pattern is supposed to be played
    // * look up the pattern
    // * schedule the pattern's events
    // * add the pattern's events to `noteEvents`
    patternEvents.forEach { event ->
      noteEvents.addAll(schedulePattern(event, startOffset))
    }

    // Now that all the notes have been scheduled, we can start the sequencer
    // (assuming it hasn't been started already, in which case this is a no-op).
    synchronized(midi.isPlaying) {
      if (midi.isPlaying) midi.startSequencer()
    }

    // At this point, `noteEvents` should contain all of the notes we've
    // scheduled, including the values of patterns at the moment right before
    // they were scheduled.
    //
    // We can now calculate the latest note end offset, which shall be our new
    // `startOffset`.

    if (noteEvents.isEmpty())
      return _startOffset

    return noteEvents.map { it.offset + it.duration }.max()!!
  }

  init {
    // This thread schedules events on this track.
    thread {
      // Before we can schedule these events, we need to know the start offset.
      //
      // This can change dynamically, e.g. if a pattern is changed on-the-fly
      // during playback, so we defer scheduling the next buffer of events as
      // long as we can.
      //
      // When new events come in on the `eventsBufferQueue`, it may be the case
      // that previous events are still lined up to be scheduled (e.g. a pattern
      // is looping). When this is the case, the new events wait in line until
      // the previous scheduling has completed and the offset where the next
      // events should start is updated.
      var startOffset = 0
      var scheduling = ReentrantLock(true) // fairness enabled

      while (!Thread.currentThread().isInterrupted()) {
        try {
          val events = eventBufferQueue.take()

          // TODO: Give events like FinishLoop a chance to act without needing
          // to wait for the lock to be released.

          // We start a new thread here so that we can wait for the opportunity
          // to schedule new events, while the parent thread continues to
          // receive new events on the queue.
          thread {
            // Wait for the previous scheduling of events to finish.
            scheduling.lock()
            println("TRACK ${trackNumber}: startOffset is ${startOffset}")
            try {
              // Schedule events and update `startOffset` to be the offset at
              // which the next events should start (after the ones we're
              // scheduling here).
              startOffset = scheduleEvents(events, startOffset)
            } finally {
              scheduling.unlock()
            }
          }
        } catch (iex : InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
    }
  }
}

val tracks = mutableMapOf<Int, Track>()

fun track(trackNumber: Int): Track {
  if (!tracks.containsKey(trackNumber))
    tracks.put(trackNumber, Track(trackNumber))

  return tracks.get(trackNumber)!!
}

class Pattern() {
  val events = mutableListOf<Event>()
}

val patterns = mutableMapOf<String, Pattern>()

fun pattern(patternName: String): Pattern {
  if (!patterns.containsKey(patternName))
    patterns.put(patternName, Pattern())

  return patterns.get(patternName)!!
}

private fun applyUpdates(updates : Updates) {
  // debug
  println("----")
  println(updates.systemActions)
  println(updates.trackActions)
  println(updates.trackEvents)
  println(updates.patternActions)
  println(updates.patternEvents)
  println("----")

  // PHASE 1: stop/mute/clear

  if (updates.systemActions.contains(SystemAction.STOP))
    midi.stopSequencer()

  if (updates.systemActions.contains(SystemAction.CLEAR)) {
    // TODO
  }

  updates.trackActions.forEach { (trackNumber, actions) ->
    if (actions.contains(TrackAction.MUTE)) {
      // TODO
    }

    if (actions.contains(TrackAction.CLEAR)) {
      // TODO
    }
  }

  updates.patternActions.forEach { (patternName, actions) ->
    if (actions.contains(PatternAction.CLEAR)) {
      pattern(patternName).events.clear()
    }
  }

  // PHASE 2: update patterns

  updates.patternEvents.forEach { (patternName, events) ->
    pattern(patternName).events.addAll(events)
  }

  // PHASE 3: update tracks

  updates.trackEvents.forEach { (trackNumber, events) ->
    track(trackNumber).eventBufferQueue.put(events)
  }

  // PHASE 4: unmute/play

  updates.trackActions.forEach { (trackNumber, actions) ->
    if (actions.contains(TrackAction.UNMUTE)) {
      // TODO
    }
  }

  // NB: We don't actually start the sequencer here; that action needs to be
  // deferred until after a track thread finishes scheduling a buffer of events.
  if (updates.systemActions.contains(SystemAction.PLAY))
    midi.isPlaying = true
}

fun player() : Thread {
  return thread(start = false) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        val instructions = playerQueue.take()
        val updates = parseUpdates(instructions)
        applyUpdates(updates)
      } catch (iex : InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }
}
