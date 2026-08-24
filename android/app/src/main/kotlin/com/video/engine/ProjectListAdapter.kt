package com.video.engine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ProjectListAdapter(
    private val projects: List<File>,
    private val onProjectClick: (File) -> Unit,
    private val onProjectMore: ((File, View) -> Unit)? = null,
    private val onProjectDelete: ((File) -> Unit)? = null,
) : RecyclerView.Adapter<ProjectListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tag: TextView = view.findViewById(R.id.projectCardTag)
        val name: TextView = view.findViewById(R.id.projectCardName)
        val date: TextView = view.findViewById(R.id.projectCardDate)
        val moreButton: View? = view.findViewById(R.id.projectCardMoreButton)
        val icon: ImageView = view.findViewById(R.id.projectCardIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val project = projects[position]
        val isAutosave = project.isAutoSaveEntry()
        holder.tag.text = if (isAutosave) "Autosave" else "Draft"
        holder.name.text = if (isAutosave) {
            holder.itemView.context.getString(R.string.project_card_name_autosave, position + 1)
        } else {
            displayName(project)
        }
        holder.date.text = "Updated ${DATE_FORMAT.format(Date(project.lastModified()))}"
        holder.icon.setImageResource(R.drawable.ic_video_clip)

        holder.itemView.setOnClickListener { onProjectClick(project) }
        holder.moreButton?.setOnClickListener { v ->
            if (onProjectMore != null) {
                onProjectMore.invoke(project, v)
            } else {
                onProjectDelete?.invoke(project)
            }
        }
    }

    override fun getItemCount() = projects.size

    private fun displayName(project: File): String {
        val rawName = project.nameWithoutExtension
        return rawName.replace(Regex("_[0-9]{8}_[0-9]{6}$"), "")
    }

    private fun File.isAutoSaveEntry(): Boolean {
        return name.equals("autosave.vne", ignoreCase = true) ||
            parentFile?.name == "autosave" ||
            parentFile?.name == "backups"
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault())
    }
}
