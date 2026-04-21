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
    private val onProjectClick: (File) -> Unit
) : RecyclerView.Adapter<ProjectListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(android.R.id.text1)
        val date: TextView = view.findViewById(android.R.id.text2)
        val icon: ImageView = ImageView(view.context).apply { 
            setImageResource(android.R.drawable.ic_menu_slideshow)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val project = projects[position]
        holder.name.text = displayName(project)
        holder.name.setTextColor(0xFFFFFFFF.toInt())

        val sdf = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())
        holder.date.text = "Updated ${sdf.format(Date(project.lastModified()))}"
        holder.date.setTextColor(0xFF888888.toInt())

        holder.itemView.setOnClickListener { onProjectClick(project) }
    }

    override fun getItemCount() = projects.size

    private fun displayName(project: File): String {
        val rawName = project.nameWithoutExtension
        return rawName.replace(Regex("_[0-9]{8}_[0-9]{6}$"), "")
    }
}
