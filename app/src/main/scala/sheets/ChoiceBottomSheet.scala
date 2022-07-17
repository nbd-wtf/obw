package wtf.nbd.obw.sheets

import android.widget.ListView
import android.os.Bundle
import android.view.{LayoutInflater, View, ViewGroup}
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import wtf.nbd.obw.utils.OnListItemClickListener
import wtf.nbd.obw.ChoiceReceiver
import wtf.nbd.obw.R

class BaseChoiceBottomSheet(list: ListView) extends BottomSheetDialogFragment {
  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState);
    setStyle(0, R.style.BottomSheetTheme);
  }

  override def onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup,
      state: Bundle
  ): View = list
}

class ChoiceBottomSheet(list: ListView, tag: AnyRef, host: ChoiceReceiver)
    extends BaseChoiceBottomSheet(list) {
  override def onViewCreated(view: View, state: Bundle): Unit =
    list setOnItemClickListener new OnListItemClickListener {
      def onItemClicked(itemPosition: Int): Unit = {
        host.onChoiceMade(tag, itemPosition)
        dismiss
      }
    }
}
