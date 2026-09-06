package com.lizongying.mytv

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.TVList

/**
 * 频道列表浮层：左侧分组、右侧频道（带频道号），半透明背景，视频在右侧继续播放。
 * 由 MainActivity 控制显示/隐藏，替代原 leanback 全屏卡片列表的入口。
 */
class ChannelListFragment : Fragment() {

    private data class GroupData(val name: String, val positions: List<Int>)

    private var groups: List<GroupData> = emptyList()
    private var selectedGroup = 0
    private var currentPosition = 0

    private lateinit var groupView: RecyclerView
    private lateinit var channelView: RecyclerView
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.channel_list, container, false)
        groupView = view.findViewById(R.id.group_list)
        channelView = view.findViewById(R.id.channel_list)
        groupView.layoutManager = LinearLayoutManager(context)
        channelView.layoutManager = LinearLayoutManager(context)
        groupAdapter = GroupAdapter()
        channelAdapter = ChannelAdapter()
        groupView.adapter = groupAdapter
        channelView.adapter = channelAdapter
        view.visibility = View.GONE
        return view
    }

    fun show() {
        rebuild()
        view?.visibility = View.VISIBLE
        channelView.post {
            val index = groups.getOrNull(selectedGroup)?.positions?.indexOf(currentPosition) ?: -1
            if (index >= 0) {
                (channelView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(index, channelView.height / 3)
            }
            channelView.requestFocus()
        }
    }

    fun hide() {
        view?.visibility = View.GONE
    }

    private fun rebuild() {
        val list = mutableListOf<GroupData>()
        var pos = 0
        TVList.list.forEach { (name, tvs) ->
            list.add(GroupData(name, List(tvs.size) { pos++ }))
        }
        currentPosition = (activity as? MainActivity)?.currentChannelPosition() ?: 0
        selectedGroup = list.indexOfFirst { it.positions.contains(currentPosition) }
            .takeIf { it >= 0 } ?: 0
        groups = list
        groupAdapter.notifyDataSetChanged()
        channelAdapter.notifyDataSetChanged()
    }

    private fun selectGroup(position: Int) {
        if (position == selectedGroup) {
            return
        }
        val old = selectedGroup
        selectedGroup = position
        groupAdapter.notifyItemChanged(old)
        groupAdapter.notifyItemChanged(selectedGroup)
        channelAdapter.notifyDataSetChanged()
    }

    private fun playChannel(position: Int) {
        currentPosition = position
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.play(position)
        mainActivity.hideChannelList()
    }

    private fun bindFocus(backgroundView: View, textView: TextView? = null, focused: Boolean) {
        backgroundView.setBackgroundColor(
            if (focused) Color.parseColor("#334FC3F7") else Color.TRANSPARENT
        )
        textView?.setTextColor(
            if (focused) Color.WHITE else Color.parseColor("#FFE8EAED")
        )
    }

    private inner class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
    }

    private inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
            )
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            val group = groups[position]
            holder.name.text = group.name
            val selected = position == selectedGroup
            holder.name.setTextColor(
                if (selected) Color.parseColor("#FF4FC3F7") else Color.parseColor("#FFE8EAED")
            )
            bindFocus(holder.itemView, null, holder.itemView.hasFocus())

            holder.itemView.setOnClickListener {
                selectGroup(position)
                channelView.requestFocus()
            }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                bindFocus(v, null, hasFocus)
                if (hasFocus && position != selectedGroup) {
                    selectGroup(position)
                }
            }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    channelView.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
    }

    private inner class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView = view.findViewById(R.id.num)
        val name: TextView = view.findViewById(R.id.name)
        val playing: TextView = view.findViewById(R.id.playing)
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
            return ChannelHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            )
        }

        override fun getItemCount(): Int {
            return groups.getOrNull(selectedGroup)?.positions?.size ?: 0
        }

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val channelPosition = groups[selectedGroup].positions[position]
            val tv = TVList.list.values
                .flatMap { it }
                .getOrNull(channelPosition)
            holder.num.text = (channelPosition + 1).toString()
            holder.name.text = tv?.title ?: ""
            val playing = channelPosition == currentPosition
            holder.playing.visibility = if (playing) View.VISIBLE else View.GONE
            holder.name.setTextColor(
                if (playing) Color.parseColor("#FF4FC3F7") else Color.parseColor("#FFE8EAED")
            )
            bindFocus(holder.itemView, holder.name, holder.itemView.hasFocus())

            holder.itemView.setOnClickListener {
                playChannel(channelPosition)
            }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                bindFocus(v, holder.name, hasFocus)
            }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    groupView.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
    }
}
