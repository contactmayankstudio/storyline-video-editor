package com.video.engine.pro.model

class ProjectSessionHistory(
    initialSession: ProjectSession,
) {
    private val undoStack = ArrayDeque<ProjectSession>()
    private val redoStack = ArrayDeque<ProjectSession>()

    var current: ProjectSession = initialSession
        private set

    fun push(newSession: ProjectSession): ProjectSession {
        if (newSession == current) {
            return current
        }
        undoStack.addLast(current)
        current = newSession
        redoStack.clear()
        return current
    }

    fun replaceWithoutHistory(session: ProjectSession): ProjectSession {
        current = session
        undoStack.clear()
        redoStack.clear()
        return current
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): ProjectSession? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        current = undoStack.removeLast()
        return current
    }

    fun redo(): ProjectSession? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        current = redoStack.removeLast()
        return current
    }
}
