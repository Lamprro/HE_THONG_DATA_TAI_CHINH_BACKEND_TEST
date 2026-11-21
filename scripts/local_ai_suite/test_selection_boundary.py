"""Regression tests for multi-horizon hyperparameter selection time leakage."""
import unittest
import pandas as pd
from train import selection_rows

class SelectionBoundary(unittest.TestCase):
 def test_h3_late_development_labels_are_not_known_at_first_test_origin(self):
  d=pd.DataFrame({'target_end':['2023-12-31','2024-03-31','2024-06-30','2024-09-30','2024-12-31'],'label':[0,1,0,1,1]})
  self.assertEqual(selection_rows(d,'2020-01-01','2024-06-30').target_end.tolist(),['2023-12-31','2024-03-31'])
 def test_modifying_unavailable_selection_labels_does_not_change_selection_data(self):
  d=pd.DataFrame({'target_end':['2024-03-31','2024-06-30','2024-12-31'],'label':[1,0,1]});altered=d.copy();altered.loc[altered.target_end>='2024-06-30','label']=99
  pd.testing.assert_frame_equal(selection_rows(d,'2020-01-01','2024-06-30'),selection_rows(altered,'2020-01-01','2024-06-30'))
 def test_h1_equal_cutoff_is_excluded(self):
  d=pd.DataFrame({'target_end':['2024-09-30','2024-12-31'],'label':[1,0]})
  self.assertEqual(len(selection_rows(d,'2020-01-01','2024-12-31')),1)

if __name__=='__main__':unittest.main()
