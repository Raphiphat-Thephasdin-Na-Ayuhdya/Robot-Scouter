package com.supercilex.robotscouter.server.functions

import com.supercilex.robotscouter.server.modules
import com.supercilex.robotscouter.server.utils.FIRESTORE_ACTIVE_TOKENS
import com.supercilex.robotscouter.server.utils.FIRESTORE_BASE_TIMESTAMP
import com.supercilex.robotscouter.server.utils.FIRESTORE_CONTENT_ID
import com.supercilex.robotscouter.server.utils.FIRESTORE_EMAIL
import com.supercilex.robotscouter.server.utils.FIRESTORE_LAST_LOGIN
import com.supercilex.robotscouter.server.utils.FIRESTORE_METRICS
import com.supercilex.robotscouter.server.utils.FIRESTORE_OWNERS
import com.supercilex.robotscouter.server.utils.FIRESTORE_PENDING_APPROVALS
import com.supercilex.robotscouter.server.utils.FIRESTORE_PHONE_NUMBER
import com.supercilex.robotscouter.server.utils.FIRESTORE_SCOUTS
import com.supercilex.robotscouter.server.utils.FIRESTORE_SCOUT_TYPE
import com.supercilex.robotscouter.server.utils.FIRESTORE_SHARE_TOKEN_TYPE
import com.supercilex.robotscouter.server.utils.FIRESTORE_SHARE_TYPE
import com.supercilex.robotscouter.server.utils.FIRESTORE_TEAM_TYPE
import com.supercilex.robotscouter.server.utils.FIRESTORE_TEMPLATE_TYPE
import com.supercilex.robotscouter.server.utils.FIRESTORE_TIMESTAMP
import com.supercilex.robotscouter.server.utils.FIRESTORE_TYPE
import com.supercilex.robotscouter.server.utils.FieldValue
import com.supercilex.robotscouter.server.utils.batch
import com.supercilex.robotscouter.server.utils.delete
import com.supercilex.robotscouter.server.utils.deletionQueue
import com.supercilex.robotscouter.server.utils.getTeamsQuery
import com.supercilex.robotscouter.server.utils.getTemplatesQuery
import com.supercilex.robotscouter.server.utils.teams
import com.supercilex.robotscouter.server.utils.templates
import com.supercilex.robotscouter.server.utils.toMap
import com.supercilex.robotscouter.server.utils.toTeamString
import com.supercilex.robotscouter.server.utils.toTemplateString
import com.supercilex.robotscouter.server.utils.types.CollectionReference
import com.supercilex.robotscouter.server.utils.types.DeltaDocumentSnapshot
import com.supercilex.robotscouter.server.utils.types.DocumentSnapshot
import com.supercilex.robotscouter.server.utils.types.Event
import com.supercilex.robotscouter.server.utils.types.Query
import com.supercilex.robotscouter.server.utils.userPrefs
import com.supercilex.robotscouter.server.utils.users
import kotlin.js.Date
import kotlin.js.Json
import kotlin.js.Promise

private const val MAX_INACTIVE_USER_DAYS = 365
private const val MAX_INACTIVE_ANONYMOUS_USER_DAYS = 45
private const val TRASH_TIMEOUT_DAYS = 30

fun deleteUnusedData(): Promise<*> {
    console.log("Looking for users that haven't opened Robot Scouter for over a year" +
                        " or anonymous users that haven't opened Robot Scouter in over 60 days.")
    return Promise.all(arrayOf(
            deleteUnusedData(users.where(
                    FIRESTORE_LAST_LOGIN,
                    "<",
                    modules.moment().subtract(MAX_INACTIVE_USER_DAYS, "days").toDate()
            )),
            deleteUnusedData(users.where(
                    FIRESTORE_LAST_LOGIN,
                    "<",
                    modules.moment().subtract(MAX_INACTIVE_ANONYMOUS_USER_DAYS, "days").toDate()
            ).where(
                    FIRESTORE_EMAIL, "==", null
            ).where(
                    FIRESTORE_PHONE_NUMBER, "==", null
            ))
    ))
}

fun emptyTrash(): Promise<*> {
    console.log("Emptying trash for all users.")
    return deletionQueue.where(
            FIRESTORE_BASE_TIMESTAMP,
            "<",
            modules.moment().subtract(TRASH_TIMEOUT_DAYS, "days").toDate()
    ).process { processDeletion(this) }
}

fun sanitizeDeletionRequest(event: Event<DeltaDocumentSnapshot>): Promise<Any?> {
    val snapshot = event.data
    if (!snapshot.exists) return Promise.resolve<Unit?>(null)

    console.log("Sanitizing deletion request for user id ${snapshot.id}.")

    val oldestDeletionRequest = snapshot.get(FIRESTORE_BASE_TIMESTAMP) as Date? ?: Date(-1)
    val recalculatedOldestDeletionRequest = snapshot.data().findOldestDeletionTime()
    return if (oldestDeletionRequest.getTime() != recalculatedOldestDeletionRequest?.getTime()) {
        console.log("Updating oldest deletion time to $recalculatedOldestDeletionRequest.")
        snapshot.ref.update(
                FIRESTORE_BASE_TIMESTAMP,
                recalculatedOldestDeletionRequest ?: Date(0)
        )
    } else {
        Promise.resolve<Unit?>(null)
    }
}

private fun deleteUnusedData(userQuery: Query): Promise<Unit> = userQuery.process {
    console.log("Deleting all data for user:\n${JSON.stringify(data())}")

    val userId = id
    Promise.all(arrayOf(
            getTeamsQuery(userId).process {
                deleteIfSingleOwner(userId) { deleteTeam(this) }
            },
            getTemplatesQuery(userId).process {
                deleteIfSingleOwner(userId) { deleteTemplate(this) }
            }
    )).then {
        deleteUser(this)
    }
}

private fun processDeletion(request: DocumentSnapshot): Promise<Unit> {
    val userId = request.id

    fun deleteTeam(id: String) = teams.doc(id).get().then {
        if (it.exists) it.deleteIfSingleOwner(userId) { deleteTeam(this) }
    }

    fun deleteScout(teamId: String, scoutId: String) = teams.doc(teamId)
            .collection(FIRESTORE_SCOUTS)
            .doc(scoutId)
            .run {
                console.log("Deleting scout: ${this.id}")
                Promise.all(arrayOf(delete(), collection(FIRESTORE_METRICS).delete()))
            }.then { Unit }

    fun deleteTemplate(id: String, userId: String) = templates.doc(id).get().then {
        if (it.exists) it.deleteIfSingleOwner(userId) { deleteTemplate(this) }
    }

    fun deleteShareToken(data: Json, token: String): Promise<Unit> {
        fun CollectionReference.delete(): Promise<Unit> {
            @Suppress("UNCHECKED_CAST") // We know its type
            val ids = data[FIRESTORE_CONTENT_ID] as Array<String>
            return Promise.all(ids.map {
                doc(it).get().then {
                    if (it.exists) {
                        Promise.all(arrayOf(
                                it.ref.update(
                                        "$FIRESTORE_ACTIVE_TOKENS.$token",
                                        FieldValue.delete()
                                ),
                                it.ref.update(
                                        FIRESTORE_PENDING_APPROVALS,
                                        FieldValue.delete()
                                )
                        ))
                    }
                }
            }.toTypedArray()).then { Unit }
        }

        console.log("Deleting share token: $token")
        return when (data[FIRESTORE_SHARE_TYPE] as Int) {
            FIRESTORE_TEAM_TYPE -> teams.delete()
            FIRESTORE_TEMPLATE_TYPE -> templates.delete()
            else -> error("Unknown share type: ${data[FIRESTORE_SHARE_TYPE]}")
        }
    }

    val requests = request.data().sanitizedDeletionRequestData()

    return Promise.all(requests.toMap<Json>().map { (key, data) ->
        val deletionTime = data[FIRESTORE_TIMESTAMP] as Date
        if ((modules.moment().diff(deletionTime, "days") as Int) < TRASH_TIMEOUT_DAYS) {
            return@map Promise.resolve<String?>(null)
        }

        when (data[FIRESTORE_TYPE]) {
            FIRESTORE_TEAM_TYPE -> deleteTeam(key)
            FIRESTORE_SCOUT_TYPE -> deleteScout(data[FIRESTORE_CONTENT_ID] as String, key)
            FIRESTORE_TEMPLATE_TYPE -> deleteTemplate(key, userId)
            FIRESTORE_SHARE_TOKEN_TYPE -> deleteShareToken(data, key)
            else -> error("Unknown type: ${data[FIRESTORE_TYPE]}")
        }.then { key }
    }.toTypedArray()).then {
        if (it.none { it == null }) {
            request.ref.delete()
        } else {
            modules.firestore.batch {
                for (field in it.filterNotNull()) {
                    update(request.ref, field, FieldValue.delete())
                }
            }
        }
    }.then { Unit }
}

private fun deleteUser(user: DocumentSnapshot): Promise<Unit> {
    console.log("Deleting user: ${user.id}")
    return user.userPrefs.delete().then {
        user.ref.delete()
    }.then { Unit }
}

private fun deleteTeam(team: DocumentSnapshot): Promise<Unit> {
    console.log("Deleting team: ${team.toTeamString()}")
    return team.ref.collection(FIRESTORE_SCOUTS).delete {
        it.ref.collection(FIRESTORE_METRICS).delete()
    }.then {
        team.ref.delete()
    }.then { Unit }
}

private fun deleteTemplate(template: DocumentSnapshot): Promise<Unit> {
    console.log("Deleting template: ${template.toTemplateString()}")
    return template.ref.collection(FIRESTORE_METRICS).delete().then {
        template.ref.delete()
    }.then { Unit }
}

private fun Query.process(block: DocumentSnapshot.() -> Promise<*>): Promise<Unit> = get().then {
    Promise.all(it.docs.map(block).toTypedArray())
}.then { Unit }

private fun DocumentSnapshot.deleteIfSingleOwner(
        userId: String,
        delete: DocumentSnapshot.() -> Promise<*>
): Promise<*> {
    console.log("Processing deletion request for id $id.")

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE") // We know its type
    val owners = get(FIRESTORE_OWNERS) as Json
    //language=JavaScript
    return if (js("Object.keys(owners).length") as Int > 1) {
        //language=undefined
        console.log("Removing $userId's ownership of ${ref.path}")
        //language=JavaScript
        js("delete owners[userId]")
        ref.update(FIRESTORE_OWNERS, owners)
    } else {
        delete(this)
    }
}

private fun Json.findOldestDeletionTime(): Date? {
    return Date(sanitizedDeletionRequestData().toMap<Json>().map { (_, data) ->
        data[FIRESTORE_TIMESTAMP] as Date
    }.map {
        it.getTime()
    }.min() ?: return null)
}

private fun Json.sanitizedDeletionRequestData(): Json {
    @Suppress("UNUSED_VARIABLE") // Used in JS
    val requests = this
    //language=JavaScript
    js("delete requests[\"$FIRESTORE_BASE_TIMESTAMP\"]")
    return this
}
