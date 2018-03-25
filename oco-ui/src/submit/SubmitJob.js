import React, { Component } from 'react';
import { Button, Message, List } from 'semantic-ui-react'
import PropTypes from 'prop-types';
import { AuthConsumer } from '../context/AuthContext';
import JobStatus from './JobStatus';
import Job from '../job/Job';
import { executeTrade } from '../context/trade'

export default class SubmitJob extends Component {

  constructor(props) {
    super(props);
    this.state = {
      executedTrade: undefined,
      processing: false,
      error: undefined
    };
  }

  onSubmit = (auth) => {
    this.setState({processing: true });
    executeTrade(
      this.props.job,
      auth
    ).then(executedTrade => {
      this.setState({
        executedTrade: executedTrade,
        processing: false
      });
      setTimeout(() => this.setState({ executedTrade: undefined }), 5000);
    }).catch(e => {
      this.setState({
        processing: false,
        error: e
      });
      setTimeout(() => this.setState({ error: undefined }), 5000);
    });
  }

  render() {
    return (
      <div>
        <AuthConsumer>{auth => 
          <Button
            primary
            disabled={this.state.processing || !this.props.valid}
            type='submit'
            onClick={() => this.onSubmit(auth)}>
            Submit
          </Button>
        }</AuthConsumer>

        {this.state.error && 
          <Message>
            <Message.Header>
              Trade failed
            </Message.Header>
            <p>{this.state.error ? this.state.error.message : null}</p>
          </Message>
        }

        <div style = {{marginTop: "15px"}}>
          <JobStatus
            processing={this.state.processing}
            executedTrade={this.state.executedTrade}
            error={this.state.error}
          />
        </div>

        <Message>
          <Message.Header>
            Request detail
          </Message.Header>
          {
            this.props.valid
              ? (<List><Job job={this.props.job}/></List>)
              : (<p>Job is not correctly defined</p>) 
          }
        </Message>

      </div> 
    );
  }
}

SubmitJob.propTypes = {
  job: PropTypes.object.isRequired,
  valid: PropTypes.bool.isRequired,
};