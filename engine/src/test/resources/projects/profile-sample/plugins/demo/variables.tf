variable "topic_name" {
  type        = string
  description = "Topic to create"
  default     = "orders"
  validation {
    condition     = length(var.topic_name) > 0
    error_message = "Topic name is required."
  }
}

variable "unrelated" {
  type    = string
  default = "kept"
}
