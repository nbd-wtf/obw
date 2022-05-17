package com.fiatjaf.wallet.sheets

import android.view.{LayoutInflater, View, ViewGroup}
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.fiatjaf.wallet.utils.OnListItemClickListener
import com.fiatjaf.wallet.ChoiceReceiver
import android.widget.ListView
import android.os.Bundle

class BaseChoiceBottomSheet(list: ListView) extends BottomSheetDialogFragment {
  me =>
  override def onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup,
      state: Bundle
  ): View = list
}

class ChoiceBottomSheet(list: ListView, tag: AnyRef, host: ChoiceReceiver)
    extends BaseChoiceBottomSheet(list) { me =>
  override def onViewCreated(view: View, state: Bundle): Unit =
    list setOnItemClickListener new OnListItemClickListener {
      def onItemClicked(itemPosition: Int): Unit = {
        host.onChoiceMade(tag, itemPosition)
        dismiss
      }
    }
}
